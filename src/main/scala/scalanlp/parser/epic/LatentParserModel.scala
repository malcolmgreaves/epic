package scalanlp.parser.epic

import scalanlp.parser._
import features.WordShapeFeaturizer
import scalanlp.parser.InsideOutside.{ExpectedCounts=>TrueCounts}
import projections.{ProjectingSpanScorer, GrammarProjections}
import splitting.StateSplitting
import ParseChart.LogProbabilityParseChart
import scalala.tensor.dense.DenseVector
import scalala.tensor.sparse.SparseVector
import scalala.library.Library
import java.io.{FileReader, BufferedReader, File}
import io.Source

class LatentParserModel[L,L3,W](featurizer: Featurizer[L3,W],
                                root: L3,
                                val projections: GrammarProjections[L,L3],
                                knownTagWords: Iterable[(L3,W)],
                                openTags: Set[L3],
                                closedWords: Set[W]) extends ParserModel[L,W] {
  type L2 = L3
  type Inference = LatentParserInference[L,L2, W]

  val indexedFeatures: FeatureIndexer[L2, W]  = FeatureIndexer(featurizer, knownTagWords, projections)

  def emptyCounts = ParserExpectedCounts[W](new TrueCounts(projections.rules.fineIndex.size,projections.labels.fineIndex.size))

  def numFeatures = indexedFeatures.index.size

  def inferenceFromWeights(weights: DenseVector[Double]) = {
    val grammar = FeaturizedGrammar(weights,indexedFeatures)
    val lexicon = new FeaturizedLexicon(openTags, closedWords, weights, indexedFeatures)
    val parser = new CKYChartBuilder[LogProbabilityParseChart,L2,W](root, lexicon, grammar, ParseChart.logProb)

    new LatentParserInference(parser, projections)
  }

  def extractParser(weights: DenseVector[Double]):ChartParser[L,L2,W] = {
    new SimpleChartParser(inferenceFromWeights(weights).builder,new MaxConstituentDecoder[L,L2,W](projections),projections)
  }

  def expectedCountsToObjective(ecounts: ExpectedCounts) = {
    val counts = expectedCountsToFeatureVector(ecounts.trueCounts)
    (ecounts.loss,counts)
  }

  def expectedCountsToFeatureVector(ecounts: TrueCounts[W]) = {
    val result = indexedFeatures.mkDenseVector();

    def sumVectorIntoResults(vec: SparseVector[Double], v: Double) {
      var i = 0
      while (i < vec.nonzeroSize) {
        result(vec.data.indexAt(i)) += vec.data.valueAt(i) * v
        i += 1
      }
    }

    // rules
    for((r,v) <- ecounts.ruleCounts.pairsIteratorNonZero)
      sumVectorIntoResults(indexedFeatures.featuresFor(r),v)

    // lex
    for( (ctr,a) <- ecounts.wordCounts.iterator.zipWithIndex; (w,v) <- ctr.nonzero.pairs) {
      val vec = indexedFeatures.featuresFor(a,w)
      sumVectorIntoResults(vec, v)
    }

    result;
  }
}

case class LatentParserInference[L,L2,W](builder: ChartBuilder[LogProbabilityParseChart,L2,W],
                                         projections: GrammarProjections[L,L2]) extends ParserInference[L,L2,W] {

  // E[T-z|T,params]
  def goldCounts(ti: TreeInstance[L,W], spanScorer: SpanScorer[L]) = {
    val projected = new ProjectingSpanScorer(projections,spanScorer)
    val ecounts = new StateSplitting(
      builder.grammar,
      builder.lexicon).expectedCounts(ti.tree.map(projections.labels.refinementsOf _),ti.words,projected)

    new ParserExpectedCounts(ecounts)
  }



}

case class LatentParserModelFactory(parser: ParserParams.BaseParser[String],
                                    substates: File = null,
                                    numStates: Int) extends ParserModelFactory[String, String] {
  type MyModel = LatentParserModel[String,(String,Int),String]

  def split(x: String, counts: Map[String,Int], numStates: Int) = {
    for(i <- 0 until counts.getOrElse(x,numStates)) yield (x,i)
  }

  def unsplit(x: (String,Int)) = x._1

  def make(trainTrees: IndexedSeq[TreeInstance[String, String]]) = {
    val (initLexicon,initBinaries,initUnaries) = this.extractBasicCounts(trainTrees)

    val xbarParser = parser.optParser getOrElse {
      val grammar = Grammar(Library.logAndNormalizeRows(initBinaries),Library.logAndNormalizeRows(initUnaries));
      val lexicon = new SimpleLexicon(initLexicon);
      new CKYChartBuilder[LogProbabilityParseChart,String,String]("",lexicon,grammar,ParseChart.logProb);
    }

    val substateMap = if(substates != null && substates.exists) {
      val in = Source.fromFile(substates).getLines()
      val pairs = for( line <- in) yield {
        val split = line.split("\\s+")
        split(0) -> split(1).toInt
      }
      pairs.toMap + (xbarParser.root -> 1)
    } else {
      Map(xbarParser.root -> 1)
    }

    val gen = new WordShapeFeaturizer(Library.sum(initLexicon))
    val feat = new SumFeaturizer[String,String](new SimpleFeaturizer[String,String], new LexFeaturizer(gen))
    val latentFeat = new SubstateFeaturizer(feat)
    val indexedProjections = GrammarProjections(xbarParser.grammar, split(_:String,substateMap, numStates), unsplit)

    val openTags = determineOpenTags(initLexicon, indexedProjections)
    val knownTagWords = determineKnownTags(xbarParser.lexicon, indexedProjections)
    val closedWords = determineClosedWords(initLexicon)

    new LatentParserModel[String,(String,Int),String](latentFeat, ("",0), indexedProjections, knownTagWords, openTags, closedWords)
  }

}

