// See LICENSE for license details.

package mini

import chisel3._
import chisel3.util._
import chisel3.testers._

class DatapathTester(datapath: => Datapath,
                     testType: DatapathTest)
                    (implicit p: freechips.rocketchip.config.Parameters) extends BasicTester with TestUtils {
  val dut = Module(datapath)
  val ctrl = Module(new Control)
  val xlen = p(XLEN)

  dut.io.ctrl <> ctrl.io
  dut.io.host.fromhost.bits := DontCare
  dut.io.host.fromhost.valid := false.B

  override val insts = tests(testType)

  val sInit :: sRun :: Nil = Enum(2)
  val state = RegInit(sInit)
  val (cntr, done) = Counter(state === sInit, insts.size)
  val timeout = RegInit(0.U(32.W))
  val mem = Mem(1 << 20, UInt(xlen.W))
  val iaddr = dut.io.icache.req.bits.addr / (xlen / 8).U
  val daddr = dut.io.dcache.req.bits.addr / (xlen / 8).U
  val write = ((0 until (xlen / 8)) foldLeft 0.U){ (data, i) => data |
    (Mux(dut.io.dcache.req.bits.mask(i), dut.io.dcache.req.bits.data, mem(daddr)) & (BigInt(0xff) << (8 * i)).U)
  }
  dut.reset := state === sInit
  dut.io.icache.resp.bits.data := RegNext(mem(iaddr))
  dut.io.icache.resp.valid := state === sRun
  dut.io.dcache.resp.bits.data := RegNext(mem(daddr))
  dut.io.dcache.resp.valid := state === sRun

  switch(state) {
    is(sInit) {
      //装载指令到mem，从0到0x200间，mem内部按addr/4装上nop
      (0 until Const.PC_START by 4) foreach { addr =>
        mem((addr / 4).U) := (if (addr == Const.PC_EVEC + (3 << 6)) fin else nop)
      }
      //mem 从0x100 / 4 开始按cntr累加将inst放入
      mem((Const.PC_START / (xlen / 8)).U + cntr) := VecInit(insts)(cntr)
      when(done) { state := sRun }
    }
    is(sRun) {
      when(dut.io.icache.req.valid) {
        printf(s"INST[%x] => %x, iaddr: %x\n", dut.io.icache.req.bits.addr, mem(iaddr), iaddr)
      }
      when(dut.io.dcache.req.valid) {
        when(dut.io.dcache.req.bits.mask.orR) {
          mem(daddr) := write
          printf("MEM[%x] <= %x\n", dut.io.dcache.req.bits.addr, write)
        }.otherwise {
          printf("MEM[%x] => %x\n", dut.io.dcache.req.bits.addr, mem(daddr))
        }
      }
      timeout := timeout + 1.U
      assert(timeout < 100.U)
      when(dut.io.host.tohost =/= 0.U) {
        assert(dut.io.host.tohost === testResults(testType).U,
               s"* tohost: %d != ${testResults(testType)} *", dut.io.host.tohost)
        stop(); stop()
      }
    }
  }
}

class DatapathTests extends org.scalatest.FlatSpec {
  implicit val p = (new MiniConfig).toInstance
  Seq(BypassTest, ExceptionTest, DfcTest, InstGenTest) foreach { test =>
    "Datapath" should s"pass $test" in {
      assert(TesterDriver execute (() => new DatapathTester(new Datapath, test)))
    }
  }
}
