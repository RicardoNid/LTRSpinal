package Chainsaw.Crypto.RSA

import Chainsaw._
import spinal.core._
import spinal.lib._

import scala.math.{max, min}

class Add(width: Int, latency: Int) extends DSPDUTTiming[Vec[UInt], UInt] {
  override val input = in(Vec(UInt(width bits), 2))
  override val output = out(Delay(input(0) + input(1), latency)) // no carry as it is an accumulator
  override val timing = TimingInfo(1, 1, latency, 1)
}

object Add {
  def apply(width: Int, latency: Int): Add = new Add(width, latency)
}

class Mult(width: Int, latency: Int) extends DSPDUTTiming[Vec[UInt], UInt] {
  override val input = in(Vec(UInt(width bits), 2))
  override val output = out(Delay(input(0) * input(1), latency))
  override val timing = TimingInfo(1, 1, latency, 1)
}

object Mult {
  def apply(width: Int, latency: Int): Mult = new Mult(width, latency)
}

/**
 * @param baseMultiplier a pipelined multiplier
 * @param baseWidth      width of the base multiplier
 * @param baseDepth      pipeline depth of the base multiplier
 */
class MultiplierCombinator[T <: DSPDUTTiming[Vec[UInt], UInt]]( // todo note this in notion
                                                                baseWidth: Int, expansionFactor: Int,
                                                                baseMultiplier: (Int, Int) => T, multLatency: Int,
                                                                baseAdder: (Int, Int) => T, addLatency: Int
                                                              )
  extends DSPDUTTiming[Vec[UInt], UInt] {

  val groupSize = multLatency + addLatency
  val expandedWidth = baseWidth * expansionFactor

  override val input = in Vec(UInt(expandedWidth bits), 2)
  override val output = out UInt (2 * expandedWidth bits)
  val valid = Bool()
  output := U(0)
  valid := False
  override val timing = TimingInfo(1, 1, 1, 1) // todo

  // TODO: improve this, reduce redundancy
  def indexGen = {
    for (sum <- 0 until expansionFactor * 2 - 1;
         i <- max(0, sum - (expansionFactor - 1)) until min(expansionFactor, sum + 1))
    yield (Array(i, sum - i, sum), i == min(expansionFactor, sum + 1) - 1)
  }

  val mult = baseMultiplier(baseWidth, multLatency)
  mult.input.foreach(_.clearAll())
  //  val multLatency = mult.timing.latency
  val adderWidth = 2 * baseWidth + log2Up(expansionFactor)
  val add = baseAdder(adderWidth, addLatency) // at most expansionFactor partial sums would be summed together
  add.input.foreach(_.clearAll())
  //  val addLatency = add.timing.latency
  require(multLatency >= 1)
  require(addLatency >= 1)

  val Seq(indexRam0, indexRam1, sumIndexRam) = (0 until 3).map { i =>
    val indexes = indexGen.map(_._1.apply(i))
    val width = log2Up(indexes.max + 1)
    Mem(indexes.map(i => U(i, width bits)).toVector)
  }
  val offLoadIndicatorRAM = Mem(indexGen.map(_._2).map(Bool(_)).toVector)

  val pipelineCounter = Counter(groupSize)
  val pipelineCounterAfterMulAdd = Delay(pipelineCounter.value, multLatency + addLatency)
  val operationCounter = Counter(expansionFactor * expansionFactor, inc = pipelineCounter.willOverflow)
  val opCountAfterMul = Delay(operationCounter.value, multLatency)
  val opCountAfterMulAdd = Delay(operationCounter.value, multLatency + addLatency)
  val opCount0 = indexRam0(operationCounter)
  val opCount1 = indexRam1(operationCounter)
  val shiftCount = sumIndexRam(opCountAfterMulAdd)
  val offLoad = offLoadIndicatorRAM(opCountAfterMulAdd)

  val partialSumQueue =
    if (min(multLatency, addLatency) == 1) FIFOLowLatency(HardType(add.output), multLatency + 1)
    else FIFO(HardType(add.output), multLatency + 1)

  val Seq(inputRAM0, inputRAM1, outputRAM) =
    Seq(input(0), input(1), output).map(signal => Mem(HardType(signal), groupSize))

  def lowerPart[T <: BitVector](value: T) = value(baseWidth - 1 downto 0)
  def higherPart[T <: BitVector](value: T) = value(adderWidth - 1 downto baseWidth)

  val multDatapath = new Area {
    val flag = RegInit(False)
    flag := True
    when(flag) {
      pipelineCounter.increment()
      when(opCount0 === U(0) && opCount1 === U(0)) {
        mult.input(0) := input(0)(opCount0 * baseWidth, baseWidth bits)
        mult.input(1) := input(1)(opCount0 * baseWidth, baseWidth bits)
      }.otherwise {
        val operand0 = inputRAM0.readAsync(pipelineCounter.value)
        val operand1 = inputRAM1.readAsync(pipelineCounter.value)
        mult.input(0) := operand0(opCount0 * baseWidth, baseWidth bits)
        mult.input(1) := operand1(opCount1 * baseWidth, baseWidth bits)
      }
    }
  }

  val addDatapath = new Area {
    val flag = Delay(multDatapath.flag, multLatency, init = False)
    when(flag) {
      when(opCountAfterMul === U(0)) {
        add.input(0) := mult.output.resized // 2 * baseWidth -> 2 * baseWidth + log2Up(expansionFactor)
        add.input(1) := U(0)
      }.otherwise {
        add.input(0) := mult.output.resized // 2 * baseWidth -> 2 * baseWidth + log2Up(expansionFactor)
        add.input(1) := partialSumQueue.pop()
      }
    }
  }

  val QueueDataPath = new Area {
    val flag = Delay(addDatapath.flag, addLatency, init = False)
    when(flag) {
      when(shiftCount === U(2 * (expansionFactor - 1))) {
        partialSumQueue.pop() // make it empty
      }.otherwise {
        when(offLoad)(partialSumQueue.push(higherPart(add.output).resized))
          .otherwise(partialSumQueue.push(add.output))
      }
    }
  }

  val outputDatapath = new Area {
    when(QueueDataPath.flag) {
      when(offLoad) {
        when(shiftCount === U(2 * (expansionFactor - 1))) {
          // TODO: pipelineCounter should be delayed
          //          val mask = (B("1" * 2 * baseWidth) << shiftCount * baseWidth).resize(2 * expandedWidth)
          //          val input = (add.output << (shiftCount * baseWidth)).resize(2 * expandedWidth)
          //          outputRAM.write(pipelineCounterAfterMulAdd, input, mask = mask)
          outputRAM(pipelineCounterAfterMulAdd) := U(0).resize(2 * expandedWidth)
          valid := True
          output := (add.output(2 * baseWidth - 1 downto 0) ## outputRAM(pipelineCounter)(2 * expandedWidth - 2 * baseWidth - 1 downto 0)).asUInt
        }.otherwise {
          val mask = (B("1" * baseWidth) << shiftCount * baseWidth).resize(2 * expandedWidth)
          val input = (lowerPart(add.output) << (shiftCount * baseWidth)).resize(2 * expandedWidth)
          outputRAM.write(pipelineCounterAfterMulAdd, input, mask = mask)
        }
      }
    }
  }

  val inputMemDatapath = new Area {
    when(opCount0 === U(0) && opCount1 === U(0)) {
      inputRAM0(pipelineCounter) := input(0)
      inputRAM1(pipelineCounter) := input(1)
    }
  }
}

object MultiplierCombinator {
  def main(args: Array[String]): Unit = {
    VivadoSynth(new MultiplierCombinator(16, 32, Mult.apply, 1, Add.apply, 1))
  }
}
