package maker.utils

import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter
import java.io.BufferedReader
import java.io.FileReader

object FileUtils {


  def file(f : String) : File = new File(f)
  def file(f : File, d : String) : File = new File(f, d)
  def file(base : String, file : String) : File = new File(base, file)

  implicit def toRichFile(f : File) = RichFile(f)
  case class RichFile(file : File){
    def isContainedIn(dir : File) = {
      def recurse(f : File) : Boolean = {
        if (f == null) false
        else if (f == dir) true 
        else if (f == new File("/")) false 
        else recurse(f.getParentFile)
      }
      recurse(file)
    }

    def read : Iterator[String] = io.Source.fromFile(file).getLines()
    def append(s:String) {
      val stringToWrite = {
        read.toList.lastOption match {
          case Some(l) if l.endsWith("\n") => "\n" + s + "\n"
          case _ => s + "\n"
        }
      }
      appendToFile(file, stringToWrite)
    }
  }
  def findFiles(pred : File => Boolean, dirs : File*) : Set[File] = {
    def rec(file : File) : List[File] = {
      if (file.isDirectory)
        file.listFiles.toList.flatMap(rec)
      else if (pred(file))
        List(file)
      else
        Nil
    }
    dirs.toList.flatMap(rec).toSet
  }

  def findFilesWithExtension(ext : String, dirs : File*) = {
    findFiles(
      {f : File => f.getName.endsWith("." + ext)},
      dirs : _*
    )
  }
  def findFilesWithExtensions(exts : List[String], dirs : File*) =
    findFiles((f : File) => exts.exists(e => f.getName.endsWith("." + e)), dirs : _*)

  def traverseDirectories(root : File, fn : File => Unit){
    fn(root)
    root.listFiles.filter(_.isDirectory).foreach(traverseDirectories(_, fn))
  }

  def allFiles(f : File) : List[File] =
    Option(f.listFiles).map(_.toList.flatMap(x =>
      if (x.isDirectory) allFiles(x)
      else x :: Nil)
    ).getOrElse(Nil)

  def lastModifiedFileTime(files : List[File]) = 
    files.flatMap(allFiles).map(_.lastModified).sortWith(_ > _).head
  
  def fileIsLaterThan(target : File, dirs : List[File]) = {
    Log.debug("dirs = " + dirs + ", latest target time " + target.lastModified() + ", latest file time = " + lastModifiedFileTime(dirs))
    target.exists() && (target.lastModified >= lastModifiedFileTime(dirs))
  }

  def replaceInFile(file : File, placeholder : String, repl : String) = {
    val lines = file.read.map(_.replace(placeholder, repl))
    writeToFile(file, lines.mkString("\n"))
  }

  def nameAndExt(file : File) = {
    val name = file.getName
    file.getName.lastIndexOf('.') match {
      case -1 => (name, "")
      case n => val parts = name.splitAt(n); (parts._1, parts._2.substring(1))
    }
  }

  def findJars(dirs : File*) = findFilesWithExtension("jar", dirs : _*)
  def findClasses(dirs : File*) = findFilesWithExtension("class", dirs : _*)
  def findSourceFiles(dirs : File*) = findFilesWithExtensions("scala" :: "java" :: Nil, dirs : _*)
  def findJavaSourceFiles(dirs : File*) = findFilesWithExtension("java", dirs : _*)

  /**
   * Don't want to use PrintWriter as that swallows exceptions
   */
  case class RichBufferedWriter(writer : BufferedWriter){
    def println(text : String){
      writer.write(text)
      writer.newLine
    }
  }
  implicit def toRichBufferedWriter(writer : BufferedWriter) = RichBufferedWriter(writer)

  def withFileWriter(file : File)(f : BufferedWriter => _){
    if (file.getParentFile != null && !file.getParentFile.exists)
      file.getParentFile.mkdirs
    val fstream = new FileWriter(file)
    val out = new BufferedWriter(fstream)
    f(out)
    out.close()
  }

  def withFileAppender(file : File)(f : BufferedWriter => _){
    val fstream = new FileWriter(file, true)
    val out = new BufferedWriter(fstream)
    f(out)
    out.close()
  }

  def withFileLineReader(file : File)(f : String => _){
    if (file.exists()) {
      val fstream = new FileReader(file)
      val in = new BufferedReader(fstream)
      var line = in.readLine
      while (line != null) {
        f(line)
        line = in.readLine
      }
      in.close()
    }
  }
  
  def tempDir(name : String = "") = {
    val temp = File.createTempFile(name, java.lang.Long.toString(System.nanoTime))
    temp.delete
    temp.mkdirs
    temp
  }

  def recursiveDelete(file : File){
    if (file.isDirectory){
      file.listFiles.foreach(recursiveDelete)
      file.delete
    } else
      file.delete
  }

  def writeToFile(file : File, text : String) {
    withFileWriter(file){
      out : BufferedWriter =>
        out.write(text)
    }
  }

  def appendToFile(file : File, text : String){
    withFileAppender(file){
      out : BufferedWriter =>
        out.write(text)
    }
  }

  def withTempFile[A](f : File => A, deleteOnExit : Boolean = true) = {
    val file = File.createTempFile("maker-temp-file", java.lang.Long.toString(System.nanoTime))
    val result = try {
      f(file)
    } finally {
      if (deleteOnExit)
        file.delete
    }
    result
  }

  def withTempDir[A](f : File => A, deleteOnExit : Boolean = true) = {
    val result = withTempFile({
        file : File => 
          file.delete
          file.mkdir
          val result = try {
            f(file)
          } finally {
            if (deleteOnExit)
              recursiveDelete(file)
          }
          result
        },
        deleteOnExit = false
    )
    result
  }

  def extractMapFromFile[K, V](file : File, extractor : String => (K, V)) : Map[K, V] = {
    Log.debug("Extracting from " + file)
    var map = Map[K, V]()
    if (file.exists) {
      withFileLineReader(file) {
        line : String ⇒ 
          map += extractor(line)
      }
    }
    map
  }

  def writeMapToFile[K, V](file : File, map : scala.collection.Map[K, V], fn : (K, V) => String){
    withFileWriter(file) {
      out: BufferedWriter =>
        map.foreach {
          case (key, value) =>
            out.println(fn(key, value))
        }
    }
  }

  def mkdirs(dir : File) = {
    dir.mkdirs
    dir
  }

  def relativise(dir: File, file : File) : String = {
    def pathComponents(f : File) = {
      f.getAbsolutePath.split("/").filterNot(_ == "").toList
    }
    val dirComponents = pathComponents(dir)
    val fileComponents = pathComponents(file)
    val commonComponents = dirComponents.zip(fileComponents).takeWhile{
      case (d, f) => d == f
    }.map(_._1)
    val directoriesUp = List.fill(dirComponents.size - commonComponents.size)("..")
    val relativeComponents = directoriesUp ::: fileComponents.drop(commonComponents.size)
    relativeComponents.mkString("/")
  }

  def classNameFromFile(classDirectory : File, classFile : File) : String = {
    relativise(classDirectory, classFile).split('.').head.replace("/", ".")
  }
}
