// See LICENSE for license details.

package mini

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.Parameters


object Const {
  val PC_START = 0x200
  // 异常处理
  val PC_EVEC  = 0x100
}

class DatapathIO(implicit p: Parameters) extends CoreBundle()(p) {
  //数据路径，只有4个IO类
  val host = new HostIO
  val icache = Flipped(new CacheIO)
  val dcache = Flipped(new CacheIO)
  val ctrl = Flipped(new ControlSignals)
}

class Datapath(implicit val p: Parameters) extends Module with CoreParams {
  val io      = IO(new DatapathIO)
  val csr     = Module(new CSR)
  val regFile = Module(new RegFile)
  val real_DfcTile = Module(new DfcTile)
  //数据通路包含组件，下面三个的初始化方法，使用的是传递参数的方法
  val alu     = p(BuildALU)(p)
  val immGen  = p(BuildImmGen)(p)
  val brCond  = p(BuildBrCond)(p)

  import Control._

  //不同指令执行阶段的寄存器缓存
  /***** Fetch / Execute Registers *****/
  val fe_inst = RegInit(Instructions.NOP)
  val fe_pc   = Reg(UInt())

  /***** Execute / Write Back Registers *****/
  val ew_inst = RegInit(Instructions.NOP) 
  val ew_pc   = Reg(UInt())
  val ew_alu  = Reg(UInt())
  val csr_in  = Reg(UInt())

  /****** Control signals 控制信号寄存器*****/
  val st_type  = Reg(io.ctrl.st_type.cloneType)
  val ld_type  = Reg(io.ctrl.ld_type.cloneType)
  val wb_sel   = Reg(io.ctrl.wb_sel.cloneType)
  val wb_en    = Reg(Bool())
  val csr_cmd  = Reg(io.ctrl.csr_cmd.cloneType)
  val illegal  = Reg(Bool())
  val pc_check = Reg(Bool())
 
  /****** Fetch *****/
  val started = RegNext(reset.toBool)
  //指令cache或数据cache无效，stall流水
  val stall = !io.icache.resp.valid || !io.dcache.resp.valid

  //从pc_start - 4 开始
  val pc   = RegInit(Const.PC_START.U(xlen.W) - 4.U(xlen.W))

  //下一个pc值，发生expt，pc跳转到evec处
  val npc  = Mux(stall, pc, Mux(csr.io.expt, csr.io.evec,
             Mux(io.ctrl.pc_sel === PC_EPC,  csr.io.epc,
             Mux(io.ctrl.pc_sel === PC_ALU || brCond.io.taken, alu.io.sum >> 1.U << 1.U,
             Mux(io.ctrl.pc_sel === PC_0, pc, pc + 4.U)))))

  //取值判断，是否started是否kill是否brtaken是否expt，有一个例外给NOP，否则从icache取指令。
  val inst = Mux(started || io.ctrl.inst_kill || brCond.io.taken || csr.io.expt, Instructions.NOP, io.icache.resp.bits.data)
  pc                      := npc 
  io.icache.req.bits.addr := npc
  io.icache.req.bits.data := 0.U
  io.icache.req.bits.mask := 0.U
  io.icache.req.valid     := !stall
  io.icache.abort         := false.B
 
  // Pipelining
  when (!stall) {
    fe_pc   := pc
    fe_inst := inst
  }

  /****** Execute *****/
  // Decode
  // 指令译码，fetch到的指令传递到ctrl单元
  io.ctrl.inst  := fe_inst

  // regFile read
  val rd_addr  = fe_inst(11, 7)
  val rs1_addr = fe_inst(19, 15)
  val rs2_addr = fe_inst(24, 20)
  regFile.io.raddr1 := rs1_addr
  regFile.io.raddr2 := rs2_addr

  // gen immdeates
  immGen.io.inst := fe_inst
  immGen.io.sel  := io.ctrl.imm_sel

  // bypass, 数据旁路
  val wb_rd_addr = ew_inst(11, 7)
  val rs1hazard = wb_en && rs1_addr.orR && (rs1_addr === wb_rd_addr)
  val rs2hazard = wb_en && rs2_addr.orR && (rs2_addr === wb_rd_addr)
  val rs1 = Mux(wb_sel === WB_ALU && rs1hazard, ew_alu, regFile.io.rdata1) 
  val rs2 = Mux(wb_sel === WB_ALU && rs2hazard, ew_alu, regFile.io.rdata2)
 
  // ALU operations
  alu.io.A := Mux(io.ctrl.A_sel === A_RS1, rs1, fe_pc)
  alu.io.B := Mux(io.ctrl.B_sel === B_RS2, rs2, immGen.io.out)
  alu.io.alu_op := io.ctrl.alu_op

  // Branch condition calc
  brCond.io.rs1 := rs1 
  brCond.io.rs2 := rs2
  brCond.io.br_type := io.ctrl.br_type

  // D$ access
  val daddr   = Mux(stall, ew_alu, alu.io.sum) >> 2.U << 2.U
  val woffset = alu.io.sum(1) << 4.U | alu.io.sum(0) << 3.U
  io.dcache.req.valid     := !stall && (io.ctrl.st_type.orR || io.ctrl.ld_type.orR)
  io.dcache.req.bits.addr := daddr 
  io.dcache.req.bits.data := rs2 << woffset
  io.dcache.req.bits.mask := MuxLookup(Mux(stall, st_type, io.ctrl.st_type), 
              "b0000".U, Seq(
    ST_SW ->  "b1111".U,
    ST_SH -> ("b11".U << alu.io.sum(1,0)),
    ST_SB -> ("b1".U  << alu.io.sum(1,0))))
  
  // Pipelining
  when(reset.toBool || !stall && csr.io.expt) {
    st_type   := 0.U
    ld_type   := 0.U
    wb_en     := false.B
    csr_cmd   := 0.U
    illegal   := false.B
    pc_check  := false.B
  }.elsewhen(!stall && !csr.io.expt) {
    ew_pc     := fe_pc
    ew_inst   := fe_inst
    ew_alu    := alu.io.out
    //状态控制寄存器有可能使用立即数字段
    csr_in    := Mux(io.ctrl.imm_sel === IMM_Z, immGen.io.out, rs1)
    st_type   := io.ctrl.st_type
    ld_type   := io.ctrl.ld_type
    wb_sel    := io.ctrl.wb_sel
    wb_en     := io.ctrl.wb_en
    csr_cmd   := io.ctrl.csr_cmd
    illegal   := io.ctrl.illegal
    pc_check  := io.ctrl.pc_sel === PC_ALU
  }

  // Load
  val loffset = ew_alu(1) << 4.U | ew_alu(0) << 3.U
  val lshift  = io.dcache.resp.bits.data >> loffset
  val load    = MuxLookup(ld_type, io.dcache.resp.bits.data.zext, Seq(
    LD_LH  -> lshift(15, 0).asSInt, LD_LB  -> lshift(7, 0).asSInt,
    LD_LHU -> lshift(15, 0).zext,   LD_LBU -> lshift(7, 0).zext) )

  // CSR access
  csr.io.stall    := stall
  csr.io.in       := csr_in
  csr.io.cmd      := csr_cmd
  csr.io.inst     := ew_inst
  csr.io.pc       := ew_pc
  csr.io.addr     := ew_alu
  csr.io.illegal  := illegal
  csr.io.pc_check := pc_check //这个影响有关需要地址的指令
  csr.io.ld_type  := ld_type
  csr.io.st_type  := st_type
  csr.io.dfcEvent := real_DfcTile.io.interruptPost
  io.host <> csr.io.host 

  // Regfile Write
  // 写回 Regfile
  val regWrite = MuxLookup(wb_sel, ew_alu.zext, Seq(
    WB_MEM -> load,
    WB_PC4 -> (ew_pc + 4.U).zext,
    WB_CSR -> csr.io.out.zext) ).asUInt 

  regFile.io.wen   := wb_en && !stall && !csr.io.expt 
  regFile.io.waddr := wb_rd_addr
  regFile.io.wdata := regWrite

  // Abort store when there's an excpetion
  io.dcache.abort := csr.io.expt

  //----------------DFC extension----------------//
  //DfcTile Connection
  val dfc_wen = io.ctrl.dfc_wen

  //wEn
  real_DfcTile.io.wEnA      := dfc_wen(0)
  real_DfcTile.io.wEnD_Addr := dfc_wen(1)
  real_DfcTile.io.wEnD_Info := dfc_wen(2)

  //wData
  real_DfcTile.io.wData     := rs1

  //opAddr
  real_DfcTile.io.opAddr_A  := rs2
  real_DfcTile.io.opAddr_D  := rs2

  //interrupt
  csr.io.dfcRdata := real_DfcTile.io.rData

  //listenaddr = memory access
  //TODO: verify memory access, the dfcTile may need add the judgement of wrong address
  real_DfcTile.io.listenAddr := Mux(io.ctrl.st_type === ST_SW ||
                                    io.ctrl.st_type === ST_SH ||
                                    io.ctrl.st_type === ST_SB, daddr, 0.U)

  //----------------DFC extension----------------//

  //trace 打印指令和pc，以及往寄存器文件写入的内容
  if (p(Trace)) {
    printf("PC: %x, INST: %x, REG[%d] <- %x\n", ew_pc, ew_inst,
      Mux(regFile.io.wen, wb_rd_addr, 0.U),
      Mux(regFile.io.wen, regFile.io.wdata, 0.U))
  }
}
