package sparkz.core.network

import java.net.{InetAddress, InetSocketAddress}

import sparkz.core.app.{ApplicationVersionSerializer, Version}
import sparkz.core.network.peer.LocalAddressPeerFeature
import sparkz.core.serialization.SparkzSerializer
import sparkz.util.Extensions._
import sparkz.util.serialization.{Reader, Writer}

/**
  * Declared information about peer
  *
  * @param agentName       - Network agent name. May contain information about client code
  *                        stack, starting from core code-base up to the end graphical interface.
  *                        Basic format is `/Name:Version(comments)/Name:Version/.../`,
  *                        e.g. `/Ergo-Scala-client:2.0.0(iPad; U; CPU OS 3_2_1)/AndroidBuild:0.8/`
  * @param protocolVersion - Identifies protocol version being used by the node
  * @param nodeName        - Custom node name
  * @param declaredAddress - Public network address of the node if any
  * @param features        - Set of node capabilities
  */
case class PeerSpec(agentName: String,
                    protocolVersion: Version,
                    nodeName: String,
                    declaredAddress: Option[InetSocketAddress],
                    features: Seq[PeerFeature]) {

  lazy val localAddressOpt: Option[InetSocketAddress] = {
    features.collectFirst { case LocalAddressPeerFeature(addr) => addr }
  }

  def reachablePeer: Boolean = address.isDefined

  def address: Option[InetSocketAddress] = declaredAddress orElse localAddressOpt

  override def equals(obj: Any): Boolean = obj match {
    case other: PeerSpec =>
      agentName == other.agentName && protocolVersion == other.protocolVersion && nodeName == other.nodeName &&
        declaredAddress == other.declaredAddress && features.toSet == other.features.toSet
    case _ => false
  }
  override def hashCode(): Int = {
    val prime = 31
    var result = 1
    result = prime * result + agentName.##
    result = prime * result + protocolVersion.##
    result = prime * result + nodeName.##
    result
  }
}

class PeerSpecSerializer(featureSerializers: PeerFeature.Serializers) extends SparkzSerializer[PeerSpec] {
  override def serialize(obj: PeerSpec, w: Writer): Unit = {

    w.putShortString(obj.agentName)
    ApplicationVersionSerializer.serialize(obj.protocolVersion, w)
    w.putShortString(obj.nodeName)


    w.putOption(obj.declaredAddress) { (writer, isa) =>
      val addr = isa.getAddress.getAddress
      writer.put((addr.size + 4).toByteExact)
      writer.putBytes(addr)
      writer.putUInt(isa.getPort)
    }

    w.put(obj.features.size.toByteExact)
    obj.features.foreach { f =>
      w.put(f.featureId)
      val fBytes = f.bytes
      w.putUShort(fBytes.length.toShortExact)
      w.putBytes(fBytes)
    }
  }

  override def parse(r: Reader): PeerSpec = {

    val appName = r.getShortString()
    require(appName.nonEmpty)

    val protocolVersion = ApplicationVersionSerializer.parse(r)

    val nodeName = r.getShortString()

    val declaredAddressOpt = r.getOption {
      val fas = r.getUByte()
      val fa = r.getBytes(fas - 4)
      val port = r.getUInt().toIntExact
      new InetSocketAddress(InetAddress.getByAddress(fa), port)
    }

    val featuresCount = r.getByte()
    val feats = (1 to featuresCount).flatMap { _ =>
      val featId = r.getByte()
      val featBytesCount = r.getUShort().toShortExact
      val featChunk = r.getChunk(featBytesCount)
      //we ignore a feature found in the PeersData if we do not know how to parse it or failed to do that
      featureSerializers.get(featId).flatMap { featureSerializer =>
        featureSerializer.parseTry(r.newReader(featChunk)).toOption
      }
    }

    PeerSpec(appName, protocolVersion, nodeName, declaredAddressOpt, feats)
  }

}
