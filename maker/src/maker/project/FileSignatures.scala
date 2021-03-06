package maker.project

import java.io.File
import maker.utils.FileUtils._

object FileSignatures{
  def apply(file: File): FileSignatures = {
    val sigs : Map[File, Long] = extractMapFromFile(
      file,
      (line : String) => {
        val sourceFile :: hash :: Nil = line.split(":").toList
        (new File(sourceFile), hash.toLong)
      }
    )
    new FileSignatures(sigs, file)
  }
}

case class FileSignatures(private var sigs : Map[File, Long], file : File){

  def persist() {
    writeMapToFile(
      file, sigs,
      (f : File, hash : Long) => f.getPath + ":" + hash.toString
    )
  }

  def +=(sourceFile: File, hash : Long) {
    sigs = sigs.updated(sourceFile, hash)
  }

  def signature(file : File) = sigs(file)
}
