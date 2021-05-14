package Chainsaw

import spinal.core._
import spinal.core.sim._
import spinal.lib._

//override val input: Flow[Vec[Real]] = slave Flow (Vec(Real(IntRange(0, 63)), 7))
//override val output: Flow[Real] = master Flow Real(IntRange(0, 63 * 7))
//
//val wallaceTree = new WallaceTree(input.payload)
//output.payload := wallaceTree.implicitValue
//output.valid := RegNext(input.valid)
//output.valid.init(False)
//
//override val timing: TimingInfo = TimingInfo(1, 1, 1, 1)
//
//override def poke(testCase: Array[Int], input: Vec[Real]): Unit = {
//input.zip(testCase).foreach { case (real, i) => real.raw #= i }
//clockDomain.waitSampling()
//}
//
//override def peek(output: Real): Int = {
//val ret = output.raw.toInt
//clockDomain.waitSampling()
//ret
//}
//
//override def referenceModel(testCase: Array[Int]): Int = testCase.sum
//
//override def isValid(refResult: Int, dutResult: Int): Boolean = refResult == dutResult
//
//override def messageWhenInvalid(testCase: Array[Int], refResult: Int, dutResult: Int): String =
//s"golden: $refResult, yours: $dutResult"
//
//override def messageWhenValid(testCase: Array[Int], refResult: Int, dutResult: Int): String =
//s"golden: $refResult, yours: $dutResult"
//
//override type RefOwnerType = this.type

// TODO: sign extension
class WallaceTreeSim() extends Component
  with DSPSim[Vec[Real], Real, Array[Int], Int] {
  override val input: Flow[Vec[Real]] = slave Flow Vec(UIntReal(63), 7)
  override val output: Flow[Real] = master Flow UIntReal(63)

  val wallaceTree = new WallaceTree(input.payload)
  output.payload := wallaceTree.implicitValue
  output.valid := RegNext(input.valid)
  output.valid.init(False)

  override val timing: TimingInfo = TimingInfo(1, 1, 1, 1)

  override def poke(testCase: Array[Int], input: Vec[Real]): Unit = {
    input.zip(testCase).foreach { case (real, i) => real.raw #= i }
    clockDomain.waitSampling(1)
  }
  override def peek(output: Real): Int = {
    val ret = output.raw.toInt
    clockDomain.waitSampling(1)
    ret
  }

  override def referenceModel(testCase: Array[Int]): Int = testCase.sum

  override def isValid(refResult: Int, dutResult: Int): Boolean = refResult == dutResult

  override def messageWhenInvalid(testCase: Array[Int], refResult: Int, dutResult: Int): String =
    s"testCase: ${testCase.mkString(" ")}, golden: $refResult, yours: $dutResult"
  override def messageWhenValid(testCase: Array[Int], refResult: Int, dutResult: Int): String =
    s"testCase: ${testCase.mkString(" ")}, golden: $refResult, yours: $dutResult"
}

object WallaceTreeSim {
  def main(args: Array[String]): Unit = {
    SimConfig.compile(new WallaceTreeSim).doSim { dut =>
      dut.sim()
      dut.insertTestCase((0 until 7).toArray)
      val report = dut.simDone()
      println(report.log.mkString("\n"))
      println(report.validLog.mkString("\n"))
    }
  }
}