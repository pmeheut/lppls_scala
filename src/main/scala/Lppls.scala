import breeze.stats.regression.LeastSquaresRegressionResult
import com.skew.slsqp4j.functions.Vector2ScalarFunc

import scala.annotation.tailrec
import scala.math.*
import scala.util.{Random, Try}

case class Observation(time: Int, price: Double)

case class FilterRange(start: Double, end: Double)

case class FilterCondition(tcRange: FilterRange, mRange: FilterRange, wRange: FilterRange, oMin: Double, dMin: Double)

type FilterConditions = Seq[Map[String, FilterCondition]]

case class OptimizationResult(tc: Double, m: Double, w: Double, a: Double, b: Double, c: Double, c1: Double, c2: Double)

case class Indicator(tc: Double, m: Double, w: Double, a: Double, b: Double, c: Double, c1: Double, c2: Double,
                     qualified: Map[String, Boolean],
                     sign: Boolean,
                     first: Int, last: Int)

case class FunctionToMinimize(observations: Seq[Observation]) extends Vector2ScalarFunc {
  def `apply`(x: Array[Double], arg: Double*): Double = {
    val Array(tc, m, w) = x
    val leastSquareResult = Lppls.matrixEquation(observations, tc, m, w)
    val resultAsArray = leastSquareResult.coefficients.toArray
    // If we did not converge, we throw an exception
    // This is bad but we are working with external libraries so...
    resultAsArray.find(c => !c.isFinite).foreach(_ => throw new Exception("Cannot solve matrix equation"))
    val Array(a, b, c1, c2) = resultAsArray

    val sumDelta = observations.foldLeft(0.0)((previous, o) => {
      val x = Lppls.lppls(o.time, tc, m, w, a, b, c1, c2) - o.price
      previous + pow(x, 2)
    })
    sumDelta
  }
}


object Lppls {
  def lppls(t: Double, tc: Double, m: Double, w: Double, a: Double, b: Double, c1: Double, c2: Double): Double = {
    a + pow(tc - t, m) * (b + ((c1 * cos(w * log(tc - t))) + (c2 * sin(w * log(tc - t)))))
  }

  private def getTcBounds(observations: Seq[Observation],
                          lowerBoundPercentage:
                          Double, upperBoundPercentage: Double): (Double, Double) = {
    val first = observations.head.time
    val last = observations.last.time
    val delta = last - first
    val deltaMinPercentage = delta * lowerBoundPercentage
    val deltaMaxPercentage = delta * upperBoundPercentage
    val tcInitMin = last - deltaMinPercentage
    val tcInitMax = last + deltaMaxPercentage
    (tcInitMin, tcInitMax)
  }

  def matrixEquation(observations: Seq[Observation], tc: Double, m: Double, w: Double): LeastSquaresRegressionResult = {
    // See https://github.com/scalanlp/breeze/wiki/Quickstart
    // https://github.com/scalanlp/breeze/blob/master/math/src/main/scala/breeze/stats/regression/LeastSquares.scala
    // https://github.com/scalanlp/breeze/blob/master/math/src/test/scala/breeze/stats/regression/LeastSquaresTest.scala
    // https://stackoverflow.com/questions/38350173/fitting-linear-model-in-scalanlp-breeze
    // Breeze uses: https://github.com/fommil/netlib-java/
    // https://numpy.org/doc/stable/reference/generated/numpy.linalg.lstsq.html
    import breeze.linalg.*
    import breeze.numerics.*
    import breeze.stats.regression.leastSquares

    def isValid(v: DenseVector[Double]): Boolean = v.data.find(c => !c.isFinite).fold(true)(_ => false)

    val t = DenseVector(observations.map(_.time.toDouble): _*)
    val p = DenseVector(observations.map(_.price): _*)
    val deltaT: DenseVector[Double] = tc - t
    val phase: DenseVector[Double] = log(deltaT)
    val fi: DenseVector[Double] = pow(deltaT, m)
    val gi: DenseVector[Double] = fi * cos(w * phase)
    val hi: DenseVector[Double] = fi * sin(w * phase)
    if (isValid(fi) && isValid(gi) && isValid(hi)) {
      val a = DenseMatrix(DenseVector.ones[Double](deltaT.length), fi, gi, hi)
      leastSquares(a.t, p)
    } else LeastSquaresRegressionResult(DenseVector(Double.NaN), Double.NaN)
  }

  private def minimize(observations: Seq[Observation], seed: Array[Double]): Option[OptimizationResult] = {
    val seedCopy = seed.toList
    Try {
      val cofs = SpqlsMinimize(seed, FunctionToMinimize(observations))
      if (cofs.success()) {
        val Array(tc, m, w) = cofs.resultVec()
        val Array(a, b, c1, c2) = matrixEquation(observations, tc, m, w).coefficients.toArray
        val c = sqrt(c1 * c1 + c2 * c2)
        Some(OptimizationResult(tc, m, w, a, b, c, c1, c2))
      } else None
    }.getOrElse(None) // If an exeption was thrown, SpqlsMinimize failed and we return none
  }

  def fit(observations: Seq[Observation], maxSearches: Int, useDebugSeed: Boolean = false): OptimizationResult = {
    val (tcInitMin, tcInitMax) = getTcBounds(observations, 0.20, 0.20)
    val initLimits = Seq[(Double, Double)](
      (tcInitMin, tcInitMax), //tc : Critical Time
      (0, 2), //m : 0.1 ≤ m ≤ 0.9
      (1, 50), // ω : 6 ≤ ω ≤ 13
    )

    // A tail-recursive loop is a simple way to write functional code with quick exit
    // once we found the result and a limit to number of iterations
    @tailrec
    def loop(remainingCount: Int): OptimizationResult = {
      if (remainingCount > 0) {

        val seedArray = if(useDebugSeed) {
          val searchCount = maxSearches - remainingCount
          val tc = initLimits(0)(0) + (initLimits(0)(1) - initLimits(0)(0)) * searchCount / maxSearches
          val m = initLimits(1)(1) - (initLimits(1)(1) - initLimits(1)(0)) * searchCount / maxSearches
          val coeff = if (searchCount % 2 == 0) 1 else -1
          val w = (initLimits(2)(0) + initLimits(2)(1)) / 2 + coeff * 0.5 * (initLimits(2)(1) - initLimits(2)(0)) * searchCount / maxSearches
          Array(tc, m, w)
        } else {
          // Python supports random with a == b, scala does not hence the if
          val seed = initLimits.map((a, b) => if (a == b) a else Random.between(a, b))
          seed.toArray
        }

        val optimizationResult = minimize(observations, seedArray)
        optimizationResult match {
          case Some(result) => result // If we minimized, we return the result
          case None => loop(remainingCount - 1) // if not, we try again with a different random seed
        }

      } else OptimizationResult(0, 0, 0, 0, 0, 0, 0, 0)
    }

    loop(maxSearches)
  }

  private def isOinRange(tc: Double, w: Double, last: Int, oMin: Double): Boolean = {
    ((w / (2 * Pi)) * log(abs(tc / (tc - last)))) > oMin
  }

  private def isDinRange(m: Double, w: Double, b: Double, c: Double, dMin: Double): Boolean = {
    if (m <= 0 || w <= 0) false else abs((m * b) / (w * c)) > dMin
  }

  // Was _func_compute_indicator
  private def computeIndicator(observations: Seq[Observation],
                               index: Int,
                               windowSize: Int,
                               smallestWindowSize: Int,
                               increment: Int,
                               maxSearches: Int,
                               filterConditions: FilterConditions,
                               useDebugSeed: Boolean): Seq[Indicator] = {
    def compute(j: Int): Option[Indicator] = {
      val start = j * increment
      val obs = observations.drop(start).take(windowSize + index - start)
      if (obs.isEmpty) None else {
        val fitResult = fit(obs, maxSearches, useDebugSeed)
        val first = obs.head.time
        val last = obs.last.time

        val qualified: Map[String, Boolean] = filterConditions.flatMap {
          f =>
            f.map {
              (k, v) =>
                val (tcInitMin, tcInitMax) = getTcBounds(obs, v.tcRange.start, v.tcRange.end)
                val tcInRange = (fitResult.tc > last - tcInitMin) && (fitResult.tc < last + tcInitMax)
                val mInRange = (fitResult.m > v.mRange.start) && (fitResult.m < v.mRange.end)
                val wInRange = (fitResult.w > v.wRange.start) && (fitResult.w < v.wRange.end)
                val oIRange = isOinRange(fitResult.tc, fitResult.w, last, v.oMin)
                val dIRange = isDinRange(fitResult.m, fitResult.w, fitResult.b, fitResult.c, v.dMin)
                k -> (tcInRange && mInRange && wInRange && oIRange && dIRange)
            }
        }.toMap
        val sign = fitResult.b < 0
        Some(Indicator(fitResult.tc, fitResult.m, fitResult.w, fitResult.a, fitResult.b, fitResult.c,
          fitResult.c1, fitResult.c2, qualified, sign, first, last))
      }
    }

    val nfits = (windowSize - smallestWindowSize) / increment
    val result = (0 to nfits - 1).map(j => compute(j)).flatten // Flatten remove the None, i.e. the results we could not compute
    result
  }

  // Was mp_compute_indicator
  def computeSlidingIndicators(observations: Seq[Observation],
                               windowSize: Int = 80,
                               smallestWindowSize: Int = 20,
                               increment: Int = 5,
                               maxSearches: Int = 25,
                               filterConditions: FilterConditions = Seq(),
                               parallel: Boolean = false,
                               useDebugSeed: Boolean = false): List[Seq[Indicator]] = {
    import scala.collection.parallel.CollectionConverters.*

    def compute(obs: Seq[Observation], i: Int) = computeIndicator(obs, i, windowSize, smallestWindowSize, increment, maxSearches, filterConditions, useDebugSeed)

    val slidingObservations = observations.sliding(windowSize).zipWithIndex.toList // We cannot keep it as an iterator because we call length on it
    val slidingObservationsLength = slidingObservations.length
    val result = if (parallel) {
      slidingObservations.par.map(compute)
    } else {
      slidingObservations.map(compute)
    }
    result.toList
  }
}
