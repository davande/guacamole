/**
 * Licensed to Big Data Genomics (BDG) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The BDG licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.hammerlab.guacamole.commands

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.hammerlab.guacamole.Common.Arguments.SomaticCallerArgs
import org.hammerlab.guacamole.filters.PileupFilter.PileupFilterArguments
import org.hammerlab.guacamole.filters.SomaticGenotypeFilter.SomaticGenotypeFilterArguments
import org.hammerlab.guacamole.filters.{ PileupFilter, SomaticAlternateReadDepthFilter, SomaticGenotypeFilter, SomaticReadDepthFilter }
import org.hammerlab.guacamole.likelihood.Likelihood
import org.hammerlab.guacamole.pileup.Pileup
import org.hammerlab.guacamole.reads.Read
import org.hammerlab.guacamole.variants.{ AlleleConversions, AlleleEvidence, CalledSomaticAllele }
import org.hammerlab.guacamole.windowing.SlidingWindow
import org.hammerlab.guacamole.{ Common, DelayedMessages, DistributedUtil, SparkCommand }
import org.kohsuke.args4j.{ Option => Opt }

/**
 * Simple subtraction based somatic variant caller
 *
 * This takes two variant callers, calls variants on tumor and normal independently,
 * and outputs the variants in the tumor sample BUT NOT the normal sample.
 *
 * This assumes that both read sets only contain a single sample, otherwise we should compare
 * on a sample identifier when joining the genotypes
 *
 */
object SomaticStandard {

  protected class Arguments extends SomaticCallerArgs with PileupFilterArguments with SomaticGenotypeFilterArguments {

    @Opt(name = "--snv-window-range", usage = "Number of bases before and after to check for additional matches or deletions")
    var snvWindowRange: Int = 20

    @Opt(name = "--odds", usage = "Minimum log odds threshold for possible variant candidates")
    var oddsThreshold: Int = 20

  }

  object Caller extends SparkCommand[Arguments] {
    override val name = "somatic-standard"
    override val description = "call somatic variants using independent callers on tumor and normal"

    override def run(args: Arguments, sc: SparkContext): Unit = {

      val filters = Read.InputFilters(mapped = true, nonDuplicate = true, passedVendorQualityChecks = true)
      val (tumorReads, normalReads) = Common.loadTumorNormalReadsFromArguments(args, sc, filters)

      assert(tumorReads.sequenceDictionary == normalReads.sequenceDictionary,
        "Tumor and normal samples have different sequence dictionaries. Tumor dictionary: %s.\nNormal dictionary: %s."
          .format(tumorReads.sequenceDictionary, normalReads.sequenceDictionary))

      val snvWindowRange = args.snvWindowRange

      val maxMappingComplexity = args.maxMappingComplexity
      val minAlignmentForComplexity = args.minAlignmentForComplexity

      val filterMultiAllelic = args.filterMultiAllelic
      val minAlignmentQuality = args.minAlignmentQuality
      val maxReadDepth = args.maxTumorReadDepth

      val oddsThreshold = args.oddsThreshold

      val loci = Common.loci(args, normalReads)
      val lociPartitions = DistributedUtil.partitionLociAccordingToArgs(
        args,
        loci,
        tumorReads.mappedReads,
        normalReads.mappedReads
      )

      var potentialGenotypes: RDD[CalledSomaticAllele] =
        DistributedUtil.pileupFlatMapTwoRDDs[CalledSomaticAllele](
          tumorReads.mappedReads,
          normalReads.mappedReads,
          lociPartitions,
          skipEmpty = true, // skip empty pileups
          (pileupTumor, pileupNormal) =>
            findPotentialVariantAtLocus(
              pileupTumor,
              pileupNormal,
              oddsThreshold,
              maxMappingComplexity,
              minAlignmentForComplexity,
              minAlignmentQuality,
              filterMultiAllelic,
              maxReadDepth
            ).iterator
        )

      // Filter potential genotypes to min read values
      potentialGenotypes =
        SomaticReadDepthFilter(
          potentialGenotypes,
          args.minTumorReadDepth,
          args.maxTumorReadDepth,
          args.minNormalReadDepth
        )

      potentialGenotypes =
        SomaticAlternateReadDepthFilter(
          potentialGenotypes,
          args.minTumorAlternateReadDepth
        )

      potentialGenotypes.persist()
      Common.progress("Computed %,d potential genotypes".format(potentialGenotypes.count))

      val genotypeLociPartitions = DistributedUtil.partitionLociUniformly(args.parallelism, loci)
      val genotypes: RDD[CalledSomaticAllele] =
        DistributedUtil.windowFlatMapWithState[CalledSomaticAllele, CalledSomaticAllele, Option[String]](
          Seq(potentialGenotypes),
          genotypeLociPartitions,
          skipEmpty = true,
          snvWindowRange.toLong,
          None,
          removeCorrelatedGenotypes
        )
      genotypes.persist()
      Common.progress("Computed %,d genotypes after regional analysis".format(genotypes.count))

      val filteredGenotypes: RDD[CalledSomaticAllele] = SomaticGenotypeFilter(genotypes, args)
      Common.progress("Computed %,d genotypes after basic filtering".format(filteredGenotypes.count))

      Common.writeVariantsFromArguments(
        args,
        filteredGenotypes.flatMap(AlleleConversions.calledSomaticAlleleToADAMGenotype)
      )

      DelayedMessages.default.print()
    }

    /**
     * Remove genotypes if there are others in a nearby window
     *
     * @param state Unused
     * @param genotypeWindows Collection of potential genotypes in the window
     * @return Set of genotypes if there are no others in the window
     */
    def removeCorrelatedGenotypes(state: Option[String],
                                  genotypeWindows: Seq[SlidingWindow[CalledSomaticAllele]]): (Option[String], Iterator[CalledSomaticAllele]) = {
      val genotypeWindow = genotypeWindows(0)
      val locus = genotypeWindow.currentLocus
      val currentGenotypes = genotypeWindow.currentRegions.filter(_.overlapsLocus(locus))

      assert(currentGenotypes.length <= 1, "There cannot be more than one called genotype at the given locus")

      if (currentGenotypes.size == genotypeWindow.currentRegions().size) {
        (None, currentGenotypes.iterator)
      } else {
        (None, Iterator.empty)
      }
    }

    def findPotentialVariantAtLocus(tumorPileup: Pileup,
                                    normalPileup: Pileup,
                                    oddsThreshold: Int,
                                    maxMappingComplexity: Int = 100,
                                    minAlignmentForComplexity: Int = 1,
                                    minAlignmentQuality: Int = 1,
                                    filterMultiAllelic: Boolean = false,
                                    maxReadDepth: Int = Int.MaxValue): Seq[CalledSomaticAllele] = {

      val filteredNormalPileup = PileupFilter(
        normalPileup,
        filterMultiAllelic,
        maxMappingComplexity = 100,
        minAlignmentForComplexity,
        minAlignmentQuality,
        minEdgeDistance = 0,
        maxPercentAbnormalInsertSize = 100
      )

      val filteredTumorPileup = PileupFilter(
        tumorPileup,
        filterMultiAllelic,
        maxMappingComplexity,
        minAlignmentForComplexity,
        minAlignmentQuality,
        minEdgeDistance = 0,
        maxPercentAbnormalInsertSize = 100
      )

      // For now, we skip loci that have no reads mapped. We may instead want to emit NoCall in this case.
      if (filteredTumorPileup.elements.isEmpty
        || filteredNormalPileup.elements.isEmpty
        || filteredTumorPileup.depth > maxReadDepth // skip abnormally deep pileups
        || filteredNormalPileup.depth > maxReadDepth
        || filteredTumorPileup.referenceDepth == filteredTumorPileup.depth // skip computation if no alternate reads
        )
        return Seq.empty

      /**
       * Find the most likely genotype in the tumor sample
       * This is either the reference genotype or an heterozygous genotype with some alternate base
       */
      val (mostLikelyTumorGenotype, mostLikelyTumorGenotypeLikelihood) =
        Likelihood.likelihoodsOfAllPossibleGenotypesFromPileup(
          filteredTumorPileup,
          Likelihood.probabilityCorrectIncludingAlignment,
          normalize = true
        ).maxBy(_._2)

      // The following lazy vals are only evaluated if mostLikelyTumorGenotype.hasVariantAllele
      lazy val normalLikelihoods =
        Likelihood.likelihoodsOfAllPossibleGenotypesFromPileup(
          filteredNormalPileup,
          Likelihood.probabilityCorrectIgnoringAlignment,
          normalize = true).toMap
      lazy val normalVariantGenotypes = normalLikelihoods.filter(_._1.hasVariantAllele)

      // NOTE(ryan): for now, compare non-reference alleles found in tumor to the sum of all likelihoods of variant
      // genotypes in the normal sample.
      // TODO(ryan): in the future, we may want to pay closer attention to the likelihood of the most likely tumor
      // genotype in the normal sample.
      lazy val normalVariantsTotalLikelihood = normalVariantGenotypes.map(_._2).sum
      lazy val somaticOdds = mostLikelyTumorGenotypeLikelihood / normalVariantsTotalLikelihood

      if (mostLikelyTumorGenotype.hasVariantAllele && somaticOdds * 100 >= oddsThreshold) {
        for {
          // NOTE(ryan): currently only look at the first non-ref allele in the most likely tumor genotype.
          // removeCorrelatedGenotypes depends on there only being one variant per locus.
          // TODO(ryan): if we want to handle the possibility of two non-reference alleles at a locus, iterate over all
          // non-reference alleles here and rework downstream assumptions accordingly.
          allele <- mostLikelyTumorGenotype.getNonReferenceAlleles.headOption.toSeq
          tumorEvidence = AlleleEvidence(mostLikelyTumorGenotypeLikelihood, allele, filteredTumorPileup)
          normalEvidence = AlleleEvidence(normalVariantsTotalLikelihood, allele, filteredNormalPileup)
        } yield {
          CalledSomaticAllele(
            tumorPileup.sampleName,
            tumorPileup.referenceName,
            tumorPileup.locus,
            allele,
            math.log(somaticOdds),
            tumorEvidence,
            normalEvidence
          )
        }
      } else {
        Seq()
      }

    }
  }
}
