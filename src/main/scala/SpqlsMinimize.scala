import com.skew.slsqp4j.{OptimizeResult, Slsqp}
import com.skew.slsqp4j.functions.Vector2ScalarFunc

object SpqlsMinimize {
  def apply(seed: Array[Double], function: Vector2ScalarFunc, maxIteration: Int = 100): OptimizeResult = {
    // See https://github.com/skew-opensource/slsqp4j
    val slsqp = Slsqp.SlsqpBuilder().withMaxIterations(maxIteration).withObjectiveFunction(function).build
    slsqp.minimize(seed)
  }
}
