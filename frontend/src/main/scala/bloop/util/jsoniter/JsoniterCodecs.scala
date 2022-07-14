package bloop.util.jsoniter

import com.github.plokhotnyuk.jsoniter_scala.core.JsonReader
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.core.JsonWriter
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

object JsoniterCodecs {

  implicit val stringCodec: JsonValueCodec[String] = JsonCodecMaker.make

  implicit def listOf[A](implicit itemCodec: JsonValueCodec[A]): JsonValueCodec[List[A]] =
    new JsonValueCodec[List[A]] {

      override def decodeValue(in: JsonReader, default: List[A]): List[A] = {
        if (in.isNextToken('[')) {
          if (in.isNextToken(']')) default
          else {
            in.rollbackToken()
            val builder = List.newBuilder[A]
            while ({
              val a = itemCodec.decodeValue(in, itemCodec.nullValue)
              builder += a
              in.isNextToken(',')
            }) ()
            if (in.isCurrentToken(']')) builder.result()
            else in.arrayEndOrCommaError()
          }
        } else in.readNullOrTokenError(default, '[')
      }

      override def encodeValue(in: List[A], out: JsonWriter): Unit = {
        out.writeArrayStart()
        in.foreach { i =>
          itemCodec.encodeValue(i, out)
        }
        out.writeArrayEnd()
      }

      override def nullValue: List[A] = List.empty

    }
}
