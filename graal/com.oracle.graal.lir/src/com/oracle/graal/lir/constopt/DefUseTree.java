/*
 * Copyright (c) 2014, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.lir.constopt;

import java.util.*;
import java.util.function.*;

import com.oracle.graal.api.meta.*;
import com.oracle.graal.compiler.common.cfg.*;
import com.oracle.graal.lir.*;
import com.oracle.graal.lir.StandardOp.MoveOp;

/**
 * Represents def-use tree of a constant.
 */
class DefUseTree {
    private final LIRInstruction instruction;
    private final AbstractBlockBase<?> block;
    private final List<UseEntry> uses;

    public DefUseTree(LIRInstruction instruction, AbstractBlockBase<?> block) {
        assert instruction instanceof MoveOp : "Not a MoveOp: " + instruction;
        this.instruction = instruction;
        this.block = block;
        this.uses = new ArrayList<>();
    }

    public Variable getVariable() {
        return (Variable) ((MoveOp) instruction).getResult();
    }

    public JavaConstant getConstant() {
        return (JavaConstant) ((MoveOp) instruction).getInput();
    }

    public LIRInstruction getInstruction() {
        return instruction;
    }

    public AbstractBlockBase<?> getBlock() {
        return block;
    }

    @Override
    public String toString() {
        return "DefUseTree [" + instruction + "|" + block + "," + uses + "]";
    }

    public void addUsage(AbstractBlockBase<?> b, LIRInstruction inst, ValuePosition position) {
        uses.add(new UseEntry(b, inst, position.get(inst)));
    }

    public int usageCount() {
        return uses.size();
    }

    public void forEach(Consumer<? super UseEntry> action) {
        uses.forEach(action);
    }

}
