object Utils {
  def time[R](block: => R): R = time()(block)

  def time[R](message: String = "")(block: => R): R = {
    val t0 = System.currentTimeMillis()
    val result = block // call-by-name
    val t1 = System.currentTimeMillis()
    println(s"[$message] finished in ${t1 - t0} ms")
    result
  }

  def timeNano[R](block: => R): R = timeNano()(block)

  def timeNano[R](message: String = "")(block: => R): R = {
    val t0 = System.nanoTime()
    val result = block // call-by-name
    val t1 = System.nanoTime()
    println(s"[$message] finished in ${t1 - t0} ns")
    result
  }
}
