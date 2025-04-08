// See README.md for license details.

package gcd

import chisel3._
import chisel3.experimental.BundleLiterals._
import chisel3.simulator.{PeekPokeAPI, SingleBackendSimulator}
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import svsim.{CommonCompilationSettings, verilator}

object DefaultSimulator extends PeekPokeAPI {

  def simulate[T <: RawModule](
      module: => T,
      buildDir: String,
      enableWaves: Boolean = false
  )(body: (T) => Unit): Unit = {
    makeSimulator(buildDir, enableWaves)
      .simulate(module) { module =>
        module.controller.setTraceEnabled(enableWaves)
        body(module.wrapped)
      }
      .result
  }

  private class DefaultSimulator(
      val workspacePath: String,
      enableWaves: Boolean = false
  ) extends SingleBackendSimulator[verilator.Backend] {
    val backend = verilator.Backend.initializeFromProcessEnvironment()
    val tag = "default"
    val commonCompilationSettings = CommonCompilationSettings()
    val backendSpecificCompilationSettings = {
      val settings = verilator.Backend.CompilationSettings()
      if (enableWaves) {
        settings.copy(
          traceStyle = Some(verilator.Backend.CompilationSettings.TraceStyle.Vcd(traceUnderscore = false))
        )
      } else {
        settings
      }
    }
  }

  private def makeSimulator(
      buildDir: String,
      enableWaves: Boolean
  ): DefaultSimulator = {
    new DefaultSimulator(buildDir, enableWaves)
  }

}

import DefaultSimulator._

/**
  * This is a trivial example of how to run this Specification
  * From within sbt use:
  * {{{
  * testOnly gcd.GCDSpec
  * }}}
  * From a terminal shell use:
  * {{{
  * sbt 'testOnly gcd.GCDSpec'
  * }}}
  * Testing from mill:
  * {{{
  * mill chisel-gcd-example.test.testOnly gcd.GCDSpec
  * }}}
  */
class GCDSpec extends AnyFreeSpec with Matchers {

  "Gcd should calculate proper greatest common denominator" in {
    simulate(new DecoupledGcd(16), buildDir = "build", enableWaves = true) {
      dut =>
      val testValues = for { x <- 0 to 10; y <- 0 to 10} yield (x, y)
      val inputSeq = testValues.map {
        case (x, y) => (new GcdInputBundle(16)).
                        Lit(_.value1 -> x.U, _.value2 -> y.U)
      }
      val resultSeq = testValues.map {
        case (x, y) => (new GcdOutputBundle(16)).
                        Lit(_.value1 -> x.U, _.value2 -> y.U,
                        _.gcd -> BigInt(x).gcd(BigInt(y)).U)
      }

      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.clock.step()

      for (inDelay <- 0 to 3) {
        for (outDelay <- 0 to 3) {
          var sent, received, cycles, delayIn, delayOut: Int = 0
          while (sent != 100 || received != 100) {
            assert(cycles <= 2500, "timeout reached")

//println(s"dIn=$delayIn dOut=$delayOut sent=$sent recv=$received")

            if (sent < 100 && delayIn == 0) {
              dut.input.valid.poke(true.B)
              dut.input.bits.value1.poke(testValues(sent)._1.U)
              dut.input.bits.value2.poke(testValues(sent)._2.U)
              if (dut.input.ready.peek().litToBoolean) {
//println(s"send: ${testValues(sent)._1} ${testValues(sent)._2}")
                sent += 1
              } else {
//println("not ready")
              }
              delayIn = inDelay
            } else {
              dut.input.valid.poke(false.B)
              if (delayIn > 0 && dut.input.ready.peek().litToBoolean) {
                delayIn = delayIn - 1
              }
            }

            if (received < 100 && delayOut == 0) {
              dut.output.ready.poke(true.B)
              if (dut.output.valid.peek().litToBoolean) {
//println(s"recv: ${dut.output.bits.value1.peek().litValue} ${dut.output.bits.value2.peek().litValue} ${dut.output.bits.gcd.peek().litValue}")
                dut.output.bits.value1.expect(testValues(received)._1)
                dut.output.bits.value2.expect(testValues(received)._2)
                dut.output.bits.gcd.expect(
                  BigInt(testValues(received)._1).gcd(testValues(received)._2)
                )
                received += 1
              }
              delayOut = outDelay
            } else {
              dut.output.ready.poke(false.B)
              if (delayOut > 0 && dut.output.valid.peek().litToBoolean) {
                delayOut = delayOut - 1
              }
            }

            // Step the simulation forward.
            dut.clock.step()
            cycles += 1
          }
          println(s"cycles: $cycles  Delay: in=$inDelay out=$outDelay")
        }
      }
    }
  }
}
