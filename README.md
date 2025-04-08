Modified Chisel GCD Example
===========================

This project contains a modified version of the GCD example used in
the [Chisel Bootcamp](https://github.com/freechipsproject/chisel-bootcamp).

This version modifies `DecoupledGCD` to add skid buffers on both input
and output, so that the GCD computation can overlap with delays in input
and output.

The original verison (retained as `DecoupledGcdOrig` in `DecoupledGcd.scala`)
has a minimum of two dead cycles&mdash;one at the start and one at the
end&mdash;and will not start a new computation before the previous result has
been accepted.

The modified version will start a new computation whenever there is a
buffered input and the previous output has a buffer to flow into. It also
short-circuits computation for GCD(0, ...), GCD(1, ...), GCD(..., 0), and
GCD(..., 1).

As a result, this version runs over _three times as fast_ as the original
on the default test input when there's no delays on the input and no
backpressure on the output. It can run _more than twice as fast_ as the
original when there's enough delay on the input and/or backpressure on the
output.

The modified testbench adds a delay of 0 to 3 cycles after each input
is accepted, and 0 to 3 cycles after each output is received.

```
In Delay    Out Delay    Original    Modified    Speedup
--------    ---------    --------    --------    -------
   0            0           650        217         200%
   0            1           750        261         187%
   0            2           850        325         162%
   0            3           950        405         135%
   1            0           749        250         200%
   1            1           849        262         224%
   1            2           949        325         192%
   1            3          1049        405         159%
   2            0           848        314         170%
   2            1           948        316         200%
   2            2          1048        327         220%
   2            3          1148        405         183%
   3            0           947        400         137%
   3            1          1047        402         160%
   3            2          1147        404         184%
   3            3          1247        408         206%
```
