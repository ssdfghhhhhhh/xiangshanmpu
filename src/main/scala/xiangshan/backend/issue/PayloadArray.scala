/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.backend.issue

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import xiangshan._
import utils._

class PayloadArrayReadIO[T <: Data](gen: T, params: RSParams) extends Bundle {
  val addr = Input(UInt(params.numEntries.W))
  val data = Output(gen)

}

class PayloadArrayWriteIO[T <: Data](gen: T, params: RSParams) extends Bundle {
  val enable = Input(Bool())
  val addr   = Input(UInt(params.numEntries.W))
  val data   = Input(gen)

}

class PayloadArray[T <: Data](gen: T, params: RSParams)(implicit p: Parameters) extends XSModule {
  val io = IO(new Bundle {
    val read = Vec(params.numDeq + 1, new PayloadArrayReadIO(gen, params))
    val write = Vec(params.numEnq, new PayloadArrayWriteIO(gen, params))
  })

  val payload = Reg(Vec(params.numEntries, gen))
  // specialized other RS to reduce registers
  // However, they should be optimized out by the synthesis tools as well.
  val immArray = if (!params.isJump) Some(Reg(Vec(params.numEntries, UInt(12.W)))) else None

  // read ports
  io.read.map(_.data).zip(io.read.map(_.addr)).foreach {
    case (data, addr) => data := Mux1H(addr, payload)
    XSError(PopCount(addr) > 1.U, p"raddr ${Binary(addr)} is not one-hot\n")
    if (immArray.isDefined && gen.isInstanceOf[MicroOp]) {
      data.asInstanceOf[MicroOp].ctrl.imm := Mux1H(addr, immArray.get)
    }
  }

  // write ports
  for (i <- 0 until params.numEntries) {
    val wenVec = VecInit(io.write.map(w => w.enable && w.addr(i)))
    val wen = wenVec.asUInt.orR
    val wdata = Mux1H(wenVec, io.write.map(_.data))
    when (wen) {
      payload(i) := wdata
    }
    XSError(PopCount(wenVec) > 1.U, p"wenVec ${Binary(wenVec.asUInt)} is not one-hot\n")
    if (immArray.isDefined && gen.isInstanceOf[MicroOp]) {
      when (wen) {
        immArray.get(i) := wdata.asInstanceOf[MicroOp].ctrl.imm
      }
    }
  }

  for (w <- io.write) {
    // check for writing to multiple entries
    XSError(w.enable && PopCount(w.addr.asBools) =/= 1.U,
      p"write address ${Binary(w.addr)} is not one-hot\n")
    // write log
    XSDebug(w.enable, p"write to address ${OHToUInt(w.addr)}\n")
  }

}
