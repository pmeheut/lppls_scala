import me.shadaj.scalapy.py
import me.shadaj.scalapy.py.*
import scopt.OParser

import java.util.logging.{Level, Logger}
import scala.util.Using


object ComputeIndicators {
  val DataDirectory = s"${System.getProperty("user.home")}/Dropbox/lbo/crash_detection"
  val DefaultInputFile = s"$DataDirectory/df_all_fields_and_features.feather"

  val feather = py.module("feather")
  val pd = py.module("pandas")
  val np = py.module("numpy")


  case class Config(inputFile: String = DefaultInputFile,
                    outputFile: String = DefaultInputFile,
                    parallel: Boolean = true,
                    numberOfElements: Int = -1,
                    one: Boolean = false,
                    useDebugSeed: Boolean = false) {
    def all = numberOfElements <= 0
  }

  val DefaultFilterConditions = {
    Seq(Map("condition_1" -> FilterCondition(
      FilterRange(0.0, 0.1), // tc_range
      FilterRange(0, 1), //m_range
      FilterRange(4, 25), // w_range
      2.5, // O_min
      0.5, // D_min
    )))
  }

  def resultToDataFrame(observations: Seq[Observation], result: List[Seq[Indicator]], conditionName: String) = {
    val index = observations.map(_.time * 1.0)
    val prices = observations.map(_.price)
    val n = prices.length - result.length

    val posConfLst = collection.mutable.ListBuffer.fill[Double](n)(0)
    val negConfLst = collection.mutable.ListBuffer.fill[Double](n)(0)
    // We never use fit defined in the python code so we do not compute it
    // Anyhow, storing an object inside a dataframe is not the best idea

    result.foreach { r =>
      var posCount, negCount, postTrueCount, negTrueCount = 0
      val accFits = collection.mutable.ListBuffer[Indicator]()
      r.foreach { f =>
        if (f.sign) {
          posCount += 1
          if (f.qualified(conditionName)) postTrueCount += 1
        } else {
          negCount += 1
          if (f.qualified(conditionName)) negTrueCount += 1
        }
      }
      posConfLst.append(if (posCount > 0) postTrueCount.toDouble / posCount else 0d)
      negConfLst.append(if (negCount > 0) negTrueCount.toDouble / negCount else 0d)
    }

    val data = List(index, prices, posConfLst, negConfLst).transpose
    val df = pd.DataFrame(data.toPythonProxy, columns = List("idx", "price", "pos_conf", "neg_conf").toPythonProxy)
    df.set_index("idx", inplace = true)
    df
  }

  // To debug and compare with python
  def writeToFile(filename: String, result: List[Seq[Indicator]], conditionName: String): Unit = {
    import java.io.*
    import scala.util.Using

    // Shortcut to format double
    def fd(x: Double) = f"$x%.2f".replace(',', '.')

    def fb(b: Boolean) = if (b) "true" else "false"

    Using(new PrintWriter(new File(filename))) { pw =>
      result.foreach { r =>
        r.foreach { f =>
          val values = (fd(f.tc), fd(f.m), fd(f.w), fd(f.a), fd(f.b), fd(f.c1), fd(f.c2), fb(f.qualified(conditionName)), fb(f.sign), f.first, f.last)
          pw.write(values.toList.mkString(", ") + "\n")
        }
      }
    }
  }

  def parseArgs(args: Array[String]): Config = {
    val builder = OParser.builder[Config]
    val oparser = {
      import builder.*
      OParser.sequence(
        programName("ComputeIndicators"),
        opt[Unit]('s', "single_thread")
          .action((_, c) => c.copy(parallel = false))
          .text("Do not use parallel computing"),
        opt[Int]('n', "number_of_elements")
          .action((x, c) => c.copy(numberOfElements = x))
          .text("Process only the last n elements of the dataframe"),
        opt[Unit]('o', "one")
          .action((_, c) => c.copy(one = true))
          .text("Compute only one indicator for debug & benchmark"),
        opt[Unit]('u', "useDebugSeed")
          .action((_, c) => c.copy(useDebugSeed = true))
          .text("Used the same seed each time to debug"),
      )
    }

    OParser.parse(oparser, args, Config()) match {
      case Some(config) => config
      case _ => System.exit(-1); Config()
    }
  }

  def buildObservations(df: py.AnyDynamics, column: String): (Seq[Observation], Int) = {
    val tmpDf = pd.DataFrame()
    tmpDf.bracketUpdate(column, df.bracketAccess(column))
    tmpDf.dropna(inplace = true)
    val length = df.shape.bracketAccess(0).as[Int]
    val timeSpx = np.linspace(0, length - 1, length).as[Seq[Int]]
    val priceSpx = np.log(tmpDf.bracketAccess(column).values).as[Seq[Double]]
    val observations = timeSpx.zip(priceSpx).map((t, p) => Observation(t, p))
    (observations, tmpDf.first_valid_index().as[Int])
  }

  def buildDataframe(observations: Seq[Observation],
                     windowSize: Int = 66,
                     smallestWindowSize: Int = 22,
                     increment: Int = 5,
                     maxSearches: Int = 25,
                     filterConditions: Seq[Map[String, FilterCondition]] = DefaultFilterConditions)
                    (using config: Config = Config()): py.AnyDynamics = {
    val result = Lppls.computeSlidingIndicators(observations = observations,
      windowSize = windowSize,
      smallestWindowSize = smallestWindowSize,
      increment = increment,
      maxSearches = maxSearches,
      filterConditions = filterConditions,
      parallel = config.parallel,
      useDebugSeed = config.useDebugSeed)

    // Uncomment to write the results and compare with python
    //writeToFile("scala_results.txt", result, "condition_1")

    resultToDataFrame(observations, result, "condition_1")
  }

  def computeOnlyOne(df: py.AnyDynamics,
                     name: String,
                     feature: String,
                     config: Config,
                     windowSize: Int = 66,
                     smallestWindowSize: Int = 22,
                     increment: Int = 5,
                     maxSearches: Int = 25): Unit = {
    import Utils.*

    val (longTermNdxRes, firstValidIndex) = time(name) {
      val (observations, firstValidIndex) = buildObservations(df, feature)
      val result = buildDataframe(observations,
        windowSize = windowSize,
        smallestWindowSize = smallestWindowSize,
        increment = increment,
        maxSearches = maxSearches)(using config)
      (result, firstValidIndex)
    }
    println(s"First valid index=$firstValidIndex")
    feather.write_dataframe(longTermNdxRes, s"$DataDirectory/lppls_only_one_${name}_scala.feather", compression = "uncompressed", version = 2)
  }

  def compute(df: py.AnyDynamics, config: Config): py.AnyDynamics = {
    import Utils.*
    given Config = config

    val (observations, firstValidIndex) = buildObservations(df, "SPX_INDEX_PX_LAST")
    val result = time(s"computing lppls")(buildDataframe(observations = observations))
    result
  }

  def readDataFrame(inputFile: String): py.AnyDynamics = {
    feather.read_dataframe(inputFile)
  }

  def saveDataFrame(df: py.AnyDynamics, outputFile: String): Unit = {
    feather.write_dataframe(df, outputFile, compression = "uncompressed", version = 2)
  }

  def main(args: Array[String]): Unit = {
    val config = parseArgs(args)
    val df = readDataFrame(config.inputFile)
    val result = compute(df, config)
    saveDataFrame(result, config.outputFile)
  }
}
