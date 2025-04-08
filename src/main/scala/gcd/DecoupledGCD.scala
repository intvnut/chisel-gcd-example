// See README.md for license details.

package gcd

import chisel3._
import chisel3.util.Decoupled

class GcdInputBundle(val w: Int) extends Bundle {
  val value1 = UInt(w.W)
  val value2 = UInt(w.W)
}

class GcdOutputBundle(val w: Int) extends Bundle {
  val value1 = UInt(w.W)
  val value2 = UInt(w.W)
  val gcd    = UInt(w.W)
}

/**
  * Compute Gcd using subtraction method.
  *
  * Subtracts the smaller from the larger until either X or Y register
  * becomes zero, or the two registers become equal. Because the two
  * registers are equal at the end, or one of the two registers is zero,
  * we can simply bitwise-OR the two registers to report the GCD.
  *
  * This version handles stalls on the input and output with skid buffers,
  * further decoupling the input from the computation. This speeds up the
  * block significantly over the original version, three ways:
  *
  *  -- It eliminates the mandatory dead cycles at start and completion.
  *  -- It starts a new request before the previous result has been
  *     accepted.
  *  -- It queues the next input before it's ready, in case the sender
  *     has its own gaps between consecutive requests.
  */
class DecoupledGcd(width: Int) extends Module {
  val input    = IO(Flipped(Decoupled(new GcdInputBundle(width))))
  val output   = IO(Decoupled(new GcdOutputBundle(width)))

  // Input skid buffer.
  val iBufX    = Reg(UInt())
  val iBufY    = Reg(UInt())
  val iBufFull = RegInit(false.B)
  val iX       = Mux(iBufFull, iBufX, input.bits.value1)
  val iY       = Mux(iBufFull, iBufY, input.bits.value2)

  // Working registers + GCD compute wires.
  val origX    = Reg(UInt())
  val origY    = Reg(UInt())
  val workX    = Reg(UInt())
  val workY    = Reg(UInt())
  val busy     = RegInit(false.B)

  // GCD computation step.
  val diffXY   = workX -& workY
  val diffYX   = workY -& workX
  val xIsLess  = diffXY(width)
  val yIsLess  = diffYX(width)

  val xIs0Or1  = !(workX(width - 1, 1).orR)
  val yIs0Or1  = !(workY(width - 1, 1).orR)
  val xIsZero  = xIs0Or1 && !workX(0)
  val yIsZero  = yIs0Or1 && !workY(0)
  val xIsOne   = xIs0Or1 &&  workX(0)
  val yIsOne   = yIs0Or1 &&  workY(0)
  val xEqualsY = workX === workY
  val gcdValue = Mux(yIsOne, !xIsZero, workX) | Mux(xIsOne, !yIsZero, workY)
  val gcdValid = busy && (xIs0Or1 || xEqualsY || yIs0Or1)

  // Output skid buffer.
  val oBufX    = Reg(UInt())
  val oBufY    = Reg(UInt())
  val oBufGcd  = Reg(UInt())
  val oBufFull = RegInit(false.B)
  val oX       = Mux(oBufFull, oBufX,   origX)
  val oY       = Mux(oBufFull, oBufY,   origY)
  val oGcd     = Mux(oBufFull, oBufGcd, gcdValue)

  // State transition signals.
  val oValid   = gcdValid
  val oReady   = !oBufFull
  val iValid   = input.valid || iBufFull
  val iReady   = (oValid && oReady) || (input.valid && !busy)
  val iAdvance = iReady || (input.valid && !iBufFull) // Advance iBuf.

  // Input buffer updates for next cycle.
  input.ready := iReady || !iBufFull                  // Dequeue input.
  when (iAdvance) {
    iBufX    := input.bits.value1
    iBufY    := input.bits.value2
    iBufFull := Mux(input.valid === iReady, iBufFull, input.valid)
  }

  // Update working registers based on our computed state transition.
  when (iValid && iReady) {
    origX := iX
    origY := iY
    workX := iX
    workY := iY
    busy  := true.B
  } .elsewhen (busy) {
    // This formulation does not go to [0, 0] when X === Y, which allows
    // us to read the GCD off of X | Y.
    workX := Mux(yIsLess, diffXY(width - 1, 0), workX)
    workY := Mux(xIsLess, diffYX(width - 1, 0), workY)
    busy  := !gcdValid || !oReady
  }

  // Output buffer updates for next cycle
  output.bits.value1 := oX
  output.bits.value2 := oY
  output.bits.gcd    := oGcd
  output.valid       := oBufFull || gcdValid

  when (oValid && oReady) {
    oBufX    := origX
    oBufY    := origY
    oBufGcd  := gcdValue
    oBufFull := (gcdValid && !output.ready) || oBufFull
  } .elsewhen (output.ready) {
    oBufFull := false.B
  }
}

/**
  * Compute Gcd using subtraction method.
  * Subtracts the smaller from the larger until register y is zero.
  * value input register x is then the Gcd.
  * Unless first input is zero then the Gcd is y.
  * Can handle stalls on the producer or consumer side
  */
class DecoupledGcdOrig(width: Int) extends Module {
  val input = IO(Flipped(Decoupled(new GcdInputBundle(width))))
  val output = IO(Decoupled(new GcdOutputBundle(width)))

  val xInitial    = Reg(UInt())
  val yInitial    = Reg(UInt())
  val x           = Reg(UInt())
  val y           = Reg(UInt())
  val busy        = RegInit(false.B)
  val resultValid = RegInit(false.B)

  input.ready := ! busy
  output.valid := resultValid
  output.bits := DontCare

  when(busy)  {
    when(x > y) {
      x := x - y
    }.otherwise {
      y := y - x
    }
    when(x === 0.U || y === 0.U) {
      when(x === 0.U) {
        output.bits.gcd := y
      }.otherwise {
        output.bits.gcd := x
      }

      output.bits.value1 := xInitial
      output.bits.value2 := yInitial
      resultValid := true.B

      when(output.ready && resultValid) {
        busy := false.B
        resultValid := false.B
      }
    }
  }.otherwise {
    when(input.valid) {
      val bundle = input.deq()
      x := bundle.value1
      y := bundle.value2
      xInitial := bundle.value1
      yInitial := bundle.value2
      busy := true.B
    }
  }
}
