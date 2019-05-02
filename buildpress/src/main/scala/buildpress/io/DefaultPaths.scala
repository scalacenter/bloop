package buildpress.io

object DefaultPaths {
  val UserHome = AbsolutePath(System.getProperty("user.home"))
  /*val BuildpressHome = AbsolutePath(
    Option(System.getProperty("buildpress.home")).getOrElse(UserHome)
  )
   */
  //val BuildpressCacheDir = BuildpressHome.resolve("cache")
}
