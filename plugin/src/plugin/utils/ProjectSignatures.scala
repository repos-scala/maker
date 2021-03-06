package plugin.utils

import collection.immutable.Map
import maker.utils.FileUtils._
import java.io.{BufferedReader, BufferedWriter, File}
import maker.utils.Log

case class ProjectSignatures(persistFile: File, private var sigs: Map[File, Set[String]] = Map()) {

  private def initializeMapFromFile {
    var sourceFile: File = null
    var lines = Set[String]()

    val SourceFile = "([^\t]*)".r
    val Line = "\t([^\t]*)".r
    withFileLineReader(persistFile) {
      line : String ⇒ 
        line match {
          case SourceFile(fileName) => {
            if (sourceFile != null) {
              sigs = sigs.updated(sourceFile, lines)
              lines = Set[String]()
            }
            sourceFile = new File(fileName)
          }
          case Line(line) => lines += line
          case _ => throw new Exception("Don't understand line " + line + " when parsing project signatures file")
        }
    }
    if (sourceFile != null) {
      sigs = sigs.updated(sourceFile, lines)
    }
  }

  if (persistFile.exists && sigs.isEmpty) {
    initializeMapFromFile
  }

  def +=(sourceFile: File, sig: Set[String]) {
    sigs = sigs.updated(sourceFile, sig)
  }

  def signature: Map[File, Set[String]] = Map[File, Set[String]]() ++ sigs

  def filesWithChangedSigs = {
    val olderSigs = ProjectSignatures(persistFile)
    val changedFiles = Set() ++ sigs.keySet.filter {
      file => (sigs(file) != olderSigs.signature.getOrElse(file, Set[String]()))
    }
    Log.debug("Files with changed sigs " + changedFiles.mkString("\n\t", "\n\t", ""))
    Log.debug("Sig changes\n" + changeAsString(olderSigs))
    changedFiles
  }

  def persist() {
    withFileWriter(persistFile) {
      writer: BufferedWriter =>
        sigs.foreach {
          case (sourceFile, sig) =>
            writer.println(sourceFile.getPath)
            sig.foreach {
              case sigLine =>
                writer.println("\t" + sigLine)
            }
        }
    }
  }

  private def files = sigs.keys.toList.sortWith(_.toString < _.toString)

  private def sigString(sig: Set[String], prefix: String = "\n\t\t", infix: String = "\n\t\t", postfix: String = "") = sig.toList.sortWith(_ < _).mkString(prefix, infix, postfix)

  private def changeAsString(olderSigs: ProjectSignatures) = {
    val buff = new StringBuffer()
    files.foreach {
      file =>
        val oldSig = olderSigs.signature.getOrElse(file, Set())
        val newSig = sigs.getOrElse(file, Set())
        val deletedSigs = oldSig.filterNot(newSig)
        val newSigs = newSig.filterNot(oldSig)
        buff.append("\n\t" + file + "\n")
        buff.append(sigString(deletedSigs, "\n\t\t-", "\n\t\t-"))
        buff.append(sigString(newSigs, "\n\t\t+", "\n\t\t+"))
    }
    buff.toString
  }

  override def toString = {
    val buff = new StringBuffer()
    files.foreach {
      file =>
        buff.append("\n\t" + file)
        buff.append(sigString(sigs(file)))
    }
    buff.toString
  }
}
