Modified Chisel GCD Example
===========================

This project contains a modified version of the GCD example used in
the [Chisel Bootcamp](https://github.com/freechipsproject/chisel-bootcamp).

This version modifies `DecoupledGCD` to add skid buffers on both input
and output, so that the GCD computation can overlap with delays in input
and output.

The original verison (retained as `DecoupledGcdOrig` in `DecoupledGcd.scala`)
has a minimum of two dead cycles--one and the start and one at the end--and
will not start a new computation before the previous result has been accepted.

The modified version will start a new computation whenever there is a
buffered input and the previous output has a buffer to flow into.

As a result, this version runs almost twice as fast as the original on the
default test input when there's no delays on the input and no backpressure
on the output. It can run _more than twice as fast_ as the original when
there's enough delay on the input and/or backpressure on the output.

The modified testbench adds a delay of 0, 1, or 2 cycles after each input
is accepted, and 0, 1, or 2 cycles after each output is received.

```
In Delay    Out Delay    Original    Modified    Speedup
--------    ---------    --------    --------    -------
   0            0           650         371         75%
   0            1           750         399         88%
   0            2           850         436         95%
   1            0           749         385         95%
   1            1           849         400        112%
   1            2           949         436        118%
   2            0           848         417        103%
   2            1           948         419        126%
   2            2          1048         438        139%
```
