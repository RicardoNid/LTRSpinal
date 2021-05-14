package Chainsaw

import spinal.core._
import spinal.lib._

import scala.collection.mutable.ListBuffer

// TODO: sign extension
class WallaceTree(input: Vec[Real]) extends ImplicitArea[Real] {

  // build the bit table
  val tableMax = input.map(_.maxExp).max
  val tableMin = input.map(_.minExp).min

  println(input.map(_.realInfo).mkString("\n"))
  val table = ListBuffer.fill(tableMax - tableMin)(ListBuffer[Bool]())
  input.foreach(operand =>
    (operand.minExp - tableMin until operand.maxExp - tableMin)
      .foreach(i => table(i) += operand.raw(i)))

  def FA(x: Bool, y: Bool, z: Bool) = (z & (x ^ y) | x & y, x ^ y ^ z) // carry, sum

  def BuildTree(table: ListBuffer[ListBuffer[Bool]]): ListBuffer[ListBuffer[Bool]] = {
    //    println(s"before: ${table.map(_.length).mkString(" ")}")

    def doWallace(col: ListBuffer[Bool]) = {
      val carrys = ListBuffer[Bool]()
      val sums = ListBuffer[Bool]()
      val groupNum = col.length / 3
      val groups = col.grouped(3).toArray
      (0 until groupNum).foreach { i =>
        val (carry, sum) = FA(groups(i)(0), groups(i)(1), groups(i)(2))
        carrys += carry
        sums += sum
      }
      if (groupNum < groups.length) sums ++= groups.last
      (carrys, sums)
    }

    val ret = ListBuffer[ListBuffer[Bool]]()
    table.foreach { col =>
      val (carrys, sums) = doWallace(col)
      if (ret.isEmpty) {
        ret += sums
        ret += carrys
      }
      else {
        ret.last ++= sums
        ret += carrys
      }
    }
    if (ret.last.isEmpty) ret -= ret.last

    println(s"after: ${ret.map(_.length).mkString(" ")}")
    if (ret.forall(_.length < 3)) ret
    else BuildTree(ret)
  }

  val operands = BuildTree(table)
  val operandsLeft = operands.map(lb => lb(0)).asBits().asUInt
  val operandsRight = operands.map(lb => if (lb.length == 2) lb(1) else False).asBits().asUInt

  // TODO: find a better range
  val ret = Real(input.map(_.upper).sum, input.map(_.lower).sum, input.map(_.minExp).min exp)
  ret.raw := (operandsLeft + operandsRight).asSInt.resized // TODO: need to be shifted

  override def implicitValue: Real = ret
}

object WallaceTree {
  def main(args: Array[String]): Unit = {
    SpinalConfig().generateSystemVerilog(new Component {
      //      val input = in Vec (3 until 8).map(i => Real(IntRange(0, (1 << i) - 1)))
      val input = in Vec(UIntReal(63), 7)
      val output = new WallaceTree(input).implicitValue
      out(output)
    })
  }
}