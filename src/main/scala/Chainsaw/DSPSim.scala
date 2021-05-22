package Chainsaw

import spinal.core.sim.{fork, simTime, _}
import spinal.core.{Component, Data, assert}

import scala.collection.mutable

/** The report of a standard simulation, you can print it directly, or utilize its attributes
 *
 * @param trueCase  number of testCases passed
 * @param totalCase number of all testCases
 * @param log       logs on all testCases that failed, each log is a String
 * @param validLog  logs on all testCases that passed
 */
case class SimReport(trueCase: Int, totalCase: Int, log: mutable.Queue[String], validLog: mutable.Queue[String]) {
  override def toString: String = s"$trueCase/$totalCase passed, ${if (trueCase != totalCase) s"failed at: \n${log.mkString("\n")}" else "Perfect!"}"
}

trait DSPSim[inputType <: Data, outputType <: Data, testCaseType, testResultType] extends Component {

  val testCases = mutable.Queue[testCaseType]()
  val lastCase = mutable.Queue[testCaseType]()
  val refResults = mutable.Queue[testResultType]()
  val dutResults = mutable.Queue[testResultType]()

  def insertTestCase(testCase: testCaseType): Unit = testCases.enqueue(testCase)

  var trueCase = 0
  var totalCase = 0
  val log = mutable.Queue[String]() // logs when is invalid
  val validLog = mutable.Queue[String]() // logs when is valid

  val period = 2

  def simCycle = simTime() / period

  def poke(testCase: testCaseType, input: inputType)

  def peek(output: outputType): testResultType

  // folloing method defines the simulation flow
  def simInit(): Unit

  /** Thread that terminates the simulation, if no result was generated during the last protect period
   *
   * @return The report of simulation
   */
  def simDone(): SimReport

  def sim(): Unit = {
    simInit()
    driver()
    monitor()
    scoreBoard()
  }

  // following methods define validation and log strategy

  /** The function that takes the testCase and return the ground truth
   *
   * @param testCase testCase
   * @return testResult
   */
  def referenceModel(testCase: testCaseType): testResultType

  /** Define the conditions by which you regard ref and dut as the same
   *
   * @param refResult - golden truth generated by the reference model
   * @param dutResult - output generated by DUT
   * @return
   */
  def isValid(refResult: testResultType, dutResult: testResultType): Boolean

  /** Message String to log when !isValid(refResult, dutResult)
   *
   * @param testCase  - testCase corresponding to the result
   * @param refResult - golden truth generated by the reference model
   * @param dutResult - output generated by DUT
   */
  def messageWhenInvalid(testCase: testCaseType, refResult: testResultType, dutResult: testResultType): String

  def messageWhenValid(testCase: testCaseType, refResult: testResultType, dutResult: testResultType): String

  /** Define when and how the testCase is passed to the DUT and the reference model
   */
  def driver(): Unit

  /** Define when and how the testResult is fetched from the DUT
   */
  def monitor(): Unit

  /** Thread that compares ref and dut results, does assertion under test mode, and logs them under debug mode
   *
   */
  def scoreBoard(): Unit = {
    fork {
      while (true) {
        if (refResults.nonEmpty && dutResults.nonEmpty) {
          val refResult = refResults.dequeue()
          val dutResult = dutResults.dequeue()
          val testCase = lastCase.dequeue()
          if (!isValid(refResult, dutResult)) {
            log += messageWhenInvalid(testCase, refResult, dutResult)
            printlnRed(messageWhenInvalid(testCase, refResult, dutResult))
          }
          else {
            trueCase += 1
            validLog += messageWhenValid(testCase, refResult, dutResult)
            if(ChainsawDebug) printlnGreen(messageWhenValid(testCase, refResult, dutResult))
          }
          totalCase += 1
          assert(isValid(refResult, dutResult) || ChainsawDebug, messageWhenInvalid(testCase, refResult, dutResult))
        }
        clockDomain.waitSampling()
      }
    }
  }

}
