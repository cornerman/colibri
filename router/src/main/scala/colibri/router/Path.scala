// COPIED FROM
// https://github.com/clovellytech/outwatch-router/blob/a53508b256b0cdce42ec883b97d0bd113f3163bb/outwatch-router/src/main/scala/outwatch/router/Path.scala

// Completely copied from org.http4s.dsl.impl

package colibri.router

import cats.implicits._
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_8

/** Base class for path extractors. */
trait Path {
  def /(child: String)    = new /(this, child)
  def toList: List[String]
  def parent: Path
  def lastOption: Option[String]
  def startsWith(other: Path): Boolean
  def pathString: String
  def toUrlString: String = if (pathString.isEmpty) "/" else pathString
}

object Path {

  /** Constructs a path from a single string by splitting on the `'/'`
    * character.
    *
    * Leading slashes do not create an empty path segment.  This is to
    * reflect that there is no distinction between a request to
    * `http://www.example.com` from `http://www.example.com/`.
    *
    * Trailing slashes result in a path with an empty final segment,
    * unless the path is `"/"`, which is `Root`.
    *
    * Segments are URL decoded.
    *
    * {{{
    * scala> Path("").toList
    * res0: List[String] = List()
    * scala> Path("/").toList
    * res1: List[String] = List()
    * scala> Path("a").toList
    * res2: List[String] = List(a)
    * scala> Path("/a").toList
    * res3: List[String] = List(a)
    * scala> Path("/a/").toList
    * res4: List[String] = List(a, "")
    * scala> Path("//a").toList
    * res5: List[String] = List("", a)
    * scala> Path("/%2F").toList
    * res0: List[String] = List(/)
    * }}}
    */
  def apply(str: String): Path =
    if (str == "" || str == "/")
      Root
    else {
      val segments  = str.split("/", -1)
      // .head is safe because split always returns non-empty array
      val segments0 = if (segments.head == "") segments.drop(1) else segments
      segments0.foldLeft(Root: Path)((path, seg) => path / UrlCodingUtils.urlDecode(seg))
    }

  def apply(first: String, rest: String*): Path =
    rest.foldLeft(Root / first)(_ / _)

  def apply(list: List[String]): Path =
    list.foldLeft(Root: Path)(_ / _)

  def apply(left: Path, right: Path): Path =
    right.toList.foldLeft(left)(_ / _)

  def unapplySeq(path: Path): Some[List[String]] =
    Some(path.toList)
//
//  def unapplySeq[F[_]](request: Request[F]): Some[List[String]] =
//    Some(Path(request.pathInfo).toList)
}

final case class /(parent: Path, child: String) extends Path {
  lazy val toList: List[String] = parent.toList ++ List(child)

  def lastOption: Some[String] = Some(child)

  lazy val asString: String = s"${parent.pathString}/${UrlCodingUtils.pathEncode(child)}"

  override def toString: String = asString

  def pathString: String = asString

  def startsWith(other: Path): Boolean = {
    val components = other.toList
    toList.take(components.length) === components
  }
}

/** Path separator extractor:
  * {{{
  *   Path("/1/2/3/test.json") match {
  *     case "1" /: "2" /: _ =>  ...
  * }}}
  */
object /: {
  def unapply(path: Path): Option[(String, Path)] =
    path.toList match {
      case head :: tail => Some(head -> Path(tail))
      case Nil          => None
    }
}

/** Root extractor:
  * {{{
  *   Path("/") match {
  *     case Root => ...
  *   }
  * }}}
  */
case object Root extends Path {
  def toList: List[String]             = Nil
  def parent: Path                     = this
  def lastOption: None.type            = None
  override def toString                = "Root"
  def pathString: String               = ""
  def startsWith(other: Path): Boolean = other == Root
}

private[router] object UrlCodingUtils {

  private val lower         = ('a' to 'z').toSet
  private val upper         = ('A' to 'Z').toSet
  private val num           = ('0' to '9').toSet
  val Unreserved: Set[Char] = lower ++ upper ++ num ++ "-_.~"

  private val toSkip: Set[Char] = Unreserved ++ "!$&'()*+,;=:/?@"

  private val HexUpperCaseChars = (0 until 16).map { i =>
    Character.toUpperCase(Character.forDigit(i, 16))
  }

  /** Percent-encodes a string.  Depending on the parameters, this method is
    * appropriate for URI or URL form encoding.  Any resulting percent-encodings
    * are normalized to uppercase.
    *
    * @param toEncode the string to encode
    * @param charset the charset to use for characters that are percent encoded
    * @param spaceIsPlus if space is not skipped, determines whether it will be
    * rendreed as a `"+"` or a percent-encoding according to `charset`.
    * @param toSkip a predicate of characters exempt from encoding.  In typical
    * use, this is composed of all Unreserved URI characters and sometimes a
    * subset of Reserved URI characters.
    */
  def urlEncode(
      toEncode: String,
      charset: Charset = UTF_8,
      spaceIsPlus: Boolean = false,
      toSkip: Char => Boolean = toSkip,
  ): String = {
    val in  = charset.encode(toEncode)
    val out = CharBuffer.allocate((in.remaining() * 3).toInt)
    while (in.hasRemaining) {
      val c = in.get().toChar
      if (toSkip(c)) {
        out.put(c)
      } else if (c == ' ' && spaceIsPlus) {
        out.put('+')
      } else {
        out.put('%')
        out.put(HexUpperCaseChars((c >> 4) & 0xf))
        out.put(HexUpperCaseChars(c & 0xf))
      }
    }
    out.flip()
    out.toString
  }

  private val SkipEncodeInPath =
    Unreserved ++ ":@!$&'()*+,;="

  def pathEncode(s: String, charset: Charset = UTF_8): String =
    UrlCodingUtils.urlEncode(s, charset, false, SkipEncodeInPath)

  /** Percent-decodes a string.
    *
    * @param toDecode the string to decode
    * @param charset the charset of percent-encoded characters
    * @param plusIsSpace true if `'+'` is to be interpreted as a `' '`
    * @param toSkip a predicate of characters whose percent-encoded form
    * is left percent-encoded.  Almost certainly should be left empty.
    */
  def urlDecode(
      toDecode: String,
      charset: Charset = UTF_8,
      plusIsSpace: Boolean = false,
      toSkip: Char => Boolean = Function.const(false),
  ): String = {
    val in  = CharBuffer.wrap(toDecode)
    // reserve enough space for 3-byte UTF-8 characters.  4-byte characters are represented
    // as surrogate pairs of characters, and will get a luxurious 6 bytes of space.
    val out = ByteBuffer.allocate(in.remaining() * 3)
    while (in.hasRemaining) {
      val mark = in.position()
      val c    = in.get()
      if (c == '%') {
        if (in.remaining() >= 2) {
          val xc = in.get()
          val yc = in.get()
          // scalastyle:off magic.number
          val x  = Character.digit(xc, 0x10)
          val y  = Character.digit(yc, 0x10)
          // scalastyle:on magic.number
          if (x != -1 && y != -1) {
            val oo = (x << 4) + y
            if (!toSkip(oo.toChar)) {
              out.put(oo.toByte)
            } else {
              out.put('%'.toByte)
              out.put(xc.toByte)
              out.put(yc.toByte)
            }
          } else {
            out.put('%'.toByte)
            in.position(mark + 1)
          }
        } else {
          // This is an invalid encoding. Fail gracefully by treating the '%' as
          // a literal.
          out.put(c.toByte)
          while (in.hasRemaining) out.put(in.get().toByte)
        }
      } else if (c == '+' && plusIsSpace) {
        out.put(' '.toByte)
      } else {
        // normally `out.put(c.toByte)` would be enough since the url is %-encoded,
        // however there are cases where a string can be partially decoded
        // so we have to make sure the non us-ascii chars get preserved properly.
        if (this.toSkip(c)) {
          out.put(c.toByte)
        } else {
          out.put(charset.encode(String.valueOf(c)))
        }
      }
    }
    out.flip()
    charset.decode(out).toString
  }
}
