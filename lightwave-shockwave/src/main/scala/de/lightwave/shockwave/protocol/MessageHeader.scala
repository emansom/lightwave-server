package de.lightwave.shockwave.protocol

import akka.util.ByteString

/**
  * Represents the header of a shockwave message
  * @param bodyLength Length of the packet without the header
  * @param operationCode Short number that indicates the
  *                      type of message
  */
case class MessageHeader(bodyLength: Short, operationCode: Short)

object MessageHeader {
  val LENGTH = 5

  def from(header: ByteString): MessageHeader = {
    if (header.length < MessageHeader.LENGTH) {
      throw new IllegalArgumentException("Invalid header size.")
    }
    val len = NumberEncoding.decodeShort(header.slice(0, 3)) - 2
    new MessageHeader(if (len <= 0) 0.toShort else len.toShort, NumberEncoding.decodeShort(header.slice(3, 5)))
  }
}
