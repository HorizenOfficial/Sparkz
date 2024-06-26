package examples.hybrid.mining

import java.io.File

import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.ceedubs.ficus.readers.ValueReader
import sparkz.core.bytesToId
import sparkz.core.settings.SparkzSettings.readConfigFromPath
import sparkz.core.settings._
import sparkz.util.SparkzLogging

import scala.concurrent.duration._

case class HybridSettings(mining: HybridMiningSettings,
                          walletSettings: WalletSettings,
                          sparkzSettings: SparkzSettings)

case class WalletSettings(seed: String,
                          password: String,
                          walletDir: File)

case class HybridMiningSettings(offlineGeneration: Boolean,
                                targetBlockDelay: FiniteDuration,
                                blockGenerationDelay: FiniteDuration,
                                posAttachmentSize: Int,
                                rParamX10: Int,
                                initialDifficulty: BigInt) {
  lazy val MaxTarget = BigInt(1, Array.fill(32)(Byte.MinValue))
  lazy val GenesisParentId = bytesToId(Array.fill(32)(1: Byte))
}

object HybridSettings extends SparkzLogging with SettingsReaders {
  def read(userConfigPath: Option[String]): HybridSettings = {
    fromConfig(readConfigFromPath(userConfigPath, "sparkz"))
  }

  implicit val networkSettingsValueReader: ValueReader[HybridSettings] =
    (cfg: Config, path: String) => fromConfig(cfg.getConfig(path))

  private def fromConfig(config: Config): HybridSettings = {
    log.info(config.toString)
    val walletSettings = config.as[WalletSettings]("sparkz.wallet")
    val miningSettings = config.as[HybridMiningSettings]("sparkz.miner")
    val sparkzSettings = config.as[SparkzSettings]("sparkz")
    HybridSettings(miningSettings, walletSettings, sparkzSettings)
  }
}

