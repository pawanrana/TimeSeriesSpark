package edu.berkeley.cs.amplab.carat.tools

import spark._
import spark.SparkContext._
import spark.timeseries._
import edu.berkeley.cs.amplab.carat._
import java.util.concurrent.Semaphore
import scala.collection.mutable.ArrayBuffer
import scala.collection.Seq
import scala.collection.immutable.Set
import scala.collection.immutable.HashSet
import scala.collection.mutable.Map
import scala.collection.immutable.TreeMap
import collection.JavaConversions._
import com.amazonaws.services.dynamodb.model.AttributeValue
import java.io.File
import java.text.SimpleDateFormat
import java.io.ByteArrayOutputStream
import com.amazonaws.services.dynamodb.model.Key
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.FileInputStream
import java.io.FileWriter
import java.io.FileOutputStream
import edu.berkeley.cs.amplab.carat.dynamodb.DynamoAnalysisUtil
import scala.collection.immutable.TreeSet
import edu.berkeley.cs.amplab.carat.dynamodb.DynamoDbDecoder
import scala.actors.scheduler.ResizableThreadPoolScheduler
import scala.collection.mutable.HashMap
import com.esotericsoftware.kryo.Kryo

/**
 * Get a specific hog/bug by app name, and exclude data for specific dates for a specific uuid.
 *
 * @author Eemil Lagerspetz
 */

object HogBugExcludingDatesForUuid {

  // Bucketing and decimal constants
  val buckets = 100
  val smallestBucket = 0.0001
  val DECIMALS = 3
  var DEBUG = false
  val LIMIT_SPEED = false
  val ABNORMAL_RATE = 9
  val DIST_THRESHOLD = 10

  val tmpdir = "/mnt/TimeSeriesSpark-unstable/spark-temp-plots/"
  
  val dfs = "yyyy-MM-dd"
  val df = new SimpleDateFormat(dfs)
  val dateString = "plots-" + df.format(System.currentTimeMillis())

  val DATA_DIR = "data"
  val PLOTS = "plots"
  val PLOTFILES = "plotfiles"

  val Bug = "Bug"
  val Hog = "Hog"
  val Sim = "Sim"
  val Pro = "Pro"

  val BUGS = "bugs"
  val HOGS = "hogs"
  val SIM = "similarApps"
  val UUIDS = "uuIds"
    
    var anyway = false

  /**
   * Main program entry point.
   */
  def main(args: Array[String]) {
    var master = "local[1]"
    if (args != null && args.length >= 1) {
      master = args(0)
      var appName = ""
      if (args.length > 1)
        appName = args(1)

      var givenUuid1 = ""
      if (args.length > 2)
        givenUuid1 = args(2)

      var givenUuid2 = ""
      if (args.length > 3)
        givenUuid2 = args(3)

      var n = 5
      var excludedTimeRanges = new ArrayBuffer[(Long, Long)]
      while (args.length > n) {
        val time1 = args(n - 1).toLong
        val time2 = args(n).toLong
        excludedTimeRanges += ((time1, time2))
        n += 2
      }
      
      if (args.length > n-1)
        anyway=true

      val start = DynamoAnalysisUtil.start()
      
      // turn off INFO logging for spark:
      System.setProperty("hadoop.root.logger", "WARN,console")
      // This is misspelled in the spark jar log4j.properties:
      System.setProperty("log4j.threshhold", "WARN")
      // Include correct spelling to make sure
      System.setProperty("log4j.threshold", "WARN")
      // turn on ProbUtil debug logging
      System.setProperty("log4j.category.spark.timeseries.ProbUtil.threshold", "DEBUG")
      System.setProperty("log4j.appender.spark.timeseries.ProbUtil.threshold", "DEBUG")

      // Fix Spark running out of space on AWS.
      System.setProperty("spark.local.dir", tmpdir)

      //System.setProperty("spark.kryo.registrator", classOf[CaratRateRegistrator].getName)
      val sc = TimeSeriesSpark.init(master, "default", "CaratDynamoDataToPlots")
      val allRates = DynamoAnalysisUtil.getRates(sc, tmpdir)
      val ret = analyzeRateData(sc, null, allRates, appName, givenUuid1, givenUuid2, excludedTimeRanges.toArray)
      //DynamoAnalysisUtil.replaceOldRateFile(RATES_CACHED, RATES_CACHED_NEW)
      DynamoAnalysisUtil.finish(start)
    }
  }

  /**
   * Main analysis function. Called on the entire collected set of CaratRates.
   */
  def analyzeRateData(sc: SparkContext, plotDirectory:String, inputRates: RDD[CaratRate], appName:String, givenUuid1:String, givenUuid2:String, excludedTimeRanges:Array[(Long,Long)]) = {
    // cache first
    val allRates = inputRates.cache()

    // determine oses and models that appear in accepted data and use those
    val uuidToOsAndModel = new scala.collection.mutable.HashMap[String, (String, String)]
    uuidToOsAndModel ++= allRates.map(x => { (x.uuid, (x.os, x.model)) }).collect()

    val oses = uuidToOsAndModel.map(_._2._1).toSet
    val models = uuidToOsAndModel.map(_._2._2).toSet

    println("uuIds with data: " + uuidToOsAndModel.keySet.mkString(", "))
    println("oses with data: " + oses.mkString(", "))
    println("models with data: " + models.mkString(", "))

    println("Calculating aPriori.")
    val aPrioriDistribution = DynamoAnalysisUtil.getApriori(allRates)
    println("Calculated aPriori.")
    if (aPrioriDistribution.size == 0)
      println("WARN: a priori dist is empty!")
    else
      println("a priori dist:\n" + aPrioriDistribution.mkString("\n"))

    val uuidArray = uuidToOsAndModel.keySet.toArray.sortWith((s, t) => {
      s < t
    })

    val i1 = uuidArray.indexOf(givenUuid1)
    val i2 = uuidArray.indexOf(givenUuid2)

    var allHogs = new HashSet[String]
    var allBugs = new HashSet[String]

    /* Check if the given app is a hog, when excluding data from the given time ranges of the given uuid: */
    val excluded = allRates.filter(x => {
      var bad = false
      // Remove all of uuid2's samples (test device)
      if (x.uuid == givenUuid2 || x.uuid == givenUuid1)
        bad = true
      !bad
    })
    
    // did exclusion work? one uuid should be removed
    val excount = excluded.map(_.uuid).collect().toSet.size
    if (excount >= uuidArray.size)
      throw new Error("Excluded 1 uuid but excount=%s and there are %s uuids!".format(excount, uuidArray.size))
    else{
      val exSampleCount = excluded.count()
      val sampleCount = allRates.count()
      println("Excluded %d users and %d samples.".format(uuidArray.size-excount, sampleCount-exSampleCount))
    }

    val buggyArr = new ArrayBuffer[RDD[CaratRate]]
    for (k <- excludedTimeRanges)
      buggyArr += allRates.filter(x => {
        var bad = false
        // Add all of uuid2's samples (test device)
        //if (x.uuid == givenUuid2)
        //  bad = true
        if (x.uuid == givenUuid1 &&
            (k._1 < x.time1 && x.time1 < k._2) &&
          (k._1 < x.time2 && x.time2 < k._2))
          bad = true
        bad && x.allApps.contains(appName)
      })
      
    // sanity check buggy samples
      println("Buggy samples:")
      var idx = 0
    for (k <- buggyArr){
      idx+=1
      println("Day "+idx)
      val rates = k.map(x => {
       if (x.isRateRange()){
              "t1=%s t2=%s b1=%s b2=%s raterange=%s".format(x.time1, x.time2, x.batt1, x.batt2, x.rateRange.toString())
            }else
              "t1=%s t2=%s b1=%s b2=%s rate=%s".format(x.time1, x.time2, x.batt1, x.batt2, x.rate.toString())
      }).collect()
      println(rates.mkString("\n"))
    }

    val filtered = excluded.filter(_.allApps.contains(appName)).cache()
    val filteredNeg = excluded.filter(!_.allApps.contains(appName)).cache()

    // skip if counts are too low:
    val fCountStart = DynamoAnalysisUtil.start
    DynamoAnalysisUtil.finish(fCountStart, "fCount")

      if (plotDists(sc, "Hog " + appName + " running", appName + " not running", filtered, filteredNeg, aPrioriDistribution, plotDirectory, true)) {
        // this is a hog
        if (anyway){
          {
          val appNotFromUuid = filtered.filter(_.uuid != givenUuid1).cache()
          println("Reference samples:")
          val refrates = appNotFromUuid.map(x => {
           if (x.isRateRange()){
              "t1=%s t2=%s b1=%s b2=%s raterange=%s".format(x.time1, x.time2, x.batt1, x.batt2, x.rateRange.toString())
            }else
              "t1=%s t2=%s b1=%s b2=%s rate=%s".format(x.time1, x.time2, x.batt1, x.batt2, x.rate.toString())
          }).collect()
          println(refrates.mkString("\n"))
          // If it is not a hog, then generate a bug plot instead, but taking only the buggy data:
          for (k <- 0 until buggyArr.length) {
            val appFromUuid = buggyArr(k)
            val timePeriod = excludedTimeRanges(k)
            plotDists(sc, "Bug " + appName + " running on client " + i1 + "(%s to %s)".format(timePeriod._1, timePeriod._2), appName + " running on other clients", appFromUuid, appNotFromUuid, aPrioriDistribution, plotDirectory, true)
          }
        }
        }
      } else {
        {
          val appNotFromUuid = filtered.filter(_.uuid != givenUuid1).cache()
          println("Reference samples:")
          val refrates = appNotFromUuid.map(x => {
            if (x.isRateRange()){
              "t1=%s t2=%s b1=%s b2=%s raterange=%s".format(x.time1, x.time2, x.batt1, x.batt2, x.rateRange.toString())
            }else
              "t1=%s t2=%s b1=%s b2=%s rate=%s".format(x.time1, x.time2, x.batt1, x.batt2, x.rate.toString())
          }).collect()
          println(refrates.mkString("\n"))
          // If it is not a hog, then generate a bug plot instead, but taking only the buggy data:
          for (k <- 0 until buggyArr.length) {
            val appFromUuid = buggyArr(k)
            val timePeriod = excludedTimeRanges(k)
            plotDists(sc, "Bug " + appName + " running on client " + i1 + "(%s to %s)".format(timePeriod._1, timePeriod._2), appName + " running on other clients", appFromUuid, appNotFromUuid, aPrioriDistribution, plotDirectory, true)
          }
        }
      }
  }

  /* Generate a gnuplot-readable plot file of the bucketed distribution.
   * Create folders plots/data plots/plotfiles
   * Save it as "plots/data/titleWith-titleWithout".txt.
   * Also generate a plotfile called plots/plotfiles/titleWith-titleWithout.gnuplot
   */

  def plotDists(sc: SparkContext, title: String, titleNeg: String,
    one: RDD[CaratRate], two: RDD[CaratRate], aPrioriDistribution: Map[Double, Double], plotDirectory:String, isBugOrHog: Boolean) = {
    val (xmax, bucketed, bucketedNeg, ev, evNeg, evDistance) = DynamoAnalysisUtil.getDistanceAndDistributionsUnBucketed(sc, one, two, aPrioriDistribution)
    
    if (bucketed != null && bucketedNeg != null && (!isBugOrHog || evDistance > 0)) {
       println("Bug distribution:")
    val bug = bucketed.collect()
    for (k <- bug){
      println(k._1 + " " + k._2)
    }
    
    println("Reference distribution:")
    val ref = bucketedNeg.collect()
    for (k <- ref){
      println(k._1 + " " + k._2)
    }
    
      plot(title, titleNeg, xmax, bucketed, bucketedNeg, ev, evNeg, evDistance, plotDirectory)
    }else
      printf("Not plotting %s vs %s, ev=%s evNeg=%s distance=%s\n", title, titleNeg, ev, evNeg, evDistance)
    isBugOrHog && evDistance > 0
  }


  def plot(title: String, titleNeg: String, xmax: Double, distWith: RDD[(Double, Double)],
    distWithout: RDD[(Double, Double)],
    ev: Double, evNeg: Double, evDistance: Double,
    plotDirectory:String,
    apps: Seq[String] = null) {
    plotSerial(title, titleNeg, xmax, distWith, distWithout, ev, evNeg, evDistance,
      plotDirectory, apps)
  }

  def plotSerial(title: String, titleNeg: String, xmax: Double, distWith: RDD[(Double, Double)],
    distWithout: RDD[(Double, Double)],
    ev: Double, evNeg: Double, evDistance: Double, plotDirectory:String,
    apps: Seq[String] = null) {
    var fixedTitle = title
    if (title.startsWith("Hog "))
      fixedTitle = title.substring(4)
    else if (title.startsWith("Bug "))
      fixedTitle = title.substring(4)
    // bump up accuracy here so that not everything gets blurred
    val evTitle = fixedTitle + " (EV=" + ev + ")"
    val evTitleNeg = titleNeg + " (EV=" + evNeg + ")"
    printf("Plotting %s vs %s, distance=%s\n", evTitle, evTitleNeg, evDistance)
    plotFile(dateString, title, evTitle, evTitleNeg, xmax, plotDirectory)
    writeData(dateString, evTitle, distWith)
    writeData(dateString, evTitleNeg, distWithout)
    plotData(dateString, title)
  }

  def plotFile(dir: String, name: String, t1: String, t2: String, xmax: Double, plotDirectory: String) = {
    val pdir = dir + "/" + PLOTS + "/"
    val gdir = dir + "/" + PLOTFILES + "/"
    val ddir = dir + "/" + DATA_DIR + "/"
    var f = new File(pdir)
    if (!f.isDirectory() && !f.mkdirs())
      println("Failed to create " + f + " for plots!")
    else {
      f = new File(gdir)
      if (!f.isDirectory() && !f.mkdirs())
        println("Failed to create " + f + " for plots!")
      else {
        f = new File(ddir)
        if (!f.isDirectory() && !f.mkdirs())
          println("Failed to create " + f + " for plots!")
        else {
          val plotfile = new java.io.FileWriter(gdir + name + ".gnuplot")
          plotfile.write("set term postscript eps enhanced color 'Helvetica' 32\nset xtics out\n" +
            "set size 1.93,1.1\n" +
            "set logscale x\n" +
            "set xrange [0.0005:" + (xmax + 0.5) + "]\n" +
            "set xlabel \"Battery drain % / s\"\n" +
            "set ylabel \"Probability\"\n")
          if (plotDirectory != null)
            plotfile.write("set output \"" + plotDirectory + "/" + assignSubDir(plotDirectory, name) + name + ".eps\"\n")
          else
            plotfile.write("set output \"" + pdir + name + ".eps\"\n")
          plotfile.write("plot \"" + ddir + t1 + ".txt\" using 1:2 with linespoints lt rgb \"#f3b14d\" ps 3 lw 5 title \"" + t1.replace("~", "\\\\~").replace("_", "\\\\_") +
            "\", " +
            "\"" + ddir + t2 + ".txt\" using 1:2 with linespoints lt rgb \"#007777\" ps 3 lw 5 title \"" + t2.replace("~", "\\\\~").replace("_", "\\\\_")
            + "\"\n")
          plotfile.close
          true
        }
      }
    }
  }

  def assignSubDir(plotDirectory: String, name: String) = {
    val p = new File(plotDirectory)
    if (!p.isDirectory() && !p.mkdirs()) {
      ""
    } else {
      val dir = name.substring(0, 3) match {
        case Bug => { BUGS }
        case Hog => { HOGS }
        case Pro => { UUIDS }
        case Sim => { SIM }
        case _ => ""
      }
      if (dir.length() > 0) {
        val d = new File(p, dir)
        if (!d.isDirectory() && !d.mkdirs())
          ""
        else
          dir + "/"
      } else
        ""
    }
  }

  def writeData(dir: String, name: String, dist: RDD[(Double, Double)]) {
    val ddir = dir + "/" + DATA_DIR + "/"
    var f = new File(ddir)
    if (!f.isDirectory() && !f.mkdirs())
      println("Failed to create " + f + " for plots!")
    else {
      val datafile = new java.io.FileWriter(ddir + name + ".txt")

      val dataPairs = dist.collect().sortWith((x, y) => {
        x._1 < y._1
      })

      for (k <- dataPairs)
        datafile.write(k._1 + " " + k._2 + "\n")
      datafile.close
    }
  }

  def writeCorrelationFile(plotDirectory: String, name: String,
    osCorrelations: Map[String, Double],
    modelCorrelations: Map[String, Double],
    usersWith: Int, usersWithout: Int) {
    val path = plotDirectory + "/" + assignSubDir(plotDirectory, name) + name + "-correlation.txt"

    var datafile: java.io.FileWriter = null

    if (usersWith != 0 || usersWithout != 0) {
      if (datafile == null) datafile = new java.io.FileWriter(path)
      datafile.write("%s users with\n%s users without\n".format(usersWith, usersWithout))
    }

    if (modelCorrelations.size > 0 || osCorrelations.size > 0) {
      if (datafile == null) datafile = new java.io.FileWriter(path)
      if (osCorrelations.size > 0) {
        val arr = osCorrelations.toArray.sortWith((x, y) => { math.abs(x._2) < math.abs(y._2) })
        datafile.write("Correlation with OS versions:\n")
        for (k <- arr) {
          datafile.write(k._2 + " " + k._1 + "\n")
        }
      }

      if (modelCorrelations.size > 0) {
        val mArr = modelCorrelations.toArray.sortWith((x, y) => { math.abs(x._2) < math.abs(y._2) })
        datafile.write("Correlation with device models:\n")
        for (k <- mArr) {
          datafile.write(k._2 + " " + k._1 + "\n")
        }
      }
      datafile.close
    }
  }

  def plotData(dir: String, title: String) {
    val gdir = dir + "/" + PLOTFILES + "/"
    val f = new File(gdir)
    if (!f.isDirectory() && !f.mkdirs())
      println("Failed to create " + f + " for plots!")
    else {
      val temp = Runtime.getRuntime().exec(Array("gnuplot", gdir + title + ".gnuplot"))
      val err_read = new java.io.BufferedReader(new java.io.InputStreamReader(temp.getErrorStream()))
      var line = err_read.readLine()
      while (line != null) {
        println(line)
        line = err_read.readLine()
      }
      temp.waitFor()
    }
  }

  def plotSamples(title: String, plotDirectory: String, data: TreeMap[String, TreeSet[Double]]) {
    println("Plotting samples.")
    writeSampleData(dateString, title, data)
  }

  def writeSampleData(dir: String, name: String, data: TreeMap[String, TreeSet[Double]]) {
    val ddir = dir + "/" + DATA_DIR + "/"
    var f = new File(ddir)
    if (!f.isDirectory() && !f.mkdirs())
      println("Failed to create " + f + " for plots!")
    else {
      val datafile = new java.io.FileWriter(ddir + name + ".txt")
      val arr = data.toArray[(String, TreeSet[Double])]
      val ret = arr.sortWith((x, y) => {
        x._2.size > y._2.size
      })
      for (k <- ret)
        for (j <- k._2)
          datafile.write(k._1 + " " + j + "\n")
      datafile.close
    }
  }
}
