/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.graal.python.util;

import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import org.graalvm.nativeimage.ImageInfo;

import com.oracle.graal.python.builtins.objects.cell.PCell;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.memory.ByteArraySupport;
import com.oracle.truffle.api.nodes.RootNode;

public final class PythonUtils {

    public static final PCell[] NO_CLOSURE = new PCell[0];
    public static final ByteArraySupport arrayAccessor;

    static {
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) {
            arrayAccessor = ByteArraySupport.bigEndian();
        } else {
            arrayAccessor = ByteArraySupport.littleEndian();
        }
    }

    private PythonUtils() {
        // no instances
    }

    public static final String[] EMPTY_STRING_ARRAY = new String[0];
    public static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];
    public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
    public static final int[] EMPTY_INT_ARRAY = new int[0];
    public static final double[] EMPTY_DOUBLE_ARRAY = new double[0];

    /**
     * Executes System.arraycopy and puts all exceptions on the slow path.
     */
    public static void arraycopy(Object src, int srcPos, Object dest, int destPos, int length) {
        try {
            System.arraycopy(src, srcPos, dest, destPos, length);
        } catch (Throwable t) {
            // this is really unexpected and we want to break exception edges in compiled code
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw t;
        }
    }

    /**
     * Executes {@code String.getChars} and puts all exceptions on the slow path.
     */
    @TruffleBoundary
    public static void getChars(String str, int srcBegin, int srcEnd, char[] dst, int dstBegin) {
        str.getChars(srcBegin, srcEnd, dst, dstBegin);
    }

    /*
     * Replacements for JDK's exact math methods that throw the checked singleton {@link
     * OverflowException}. The implementation is taken from JDK.
     */
    public static int addExact(int x, int y) throws OverflowException {
        int r = x + y;
        if (((x ^ r) & (y ^ r)) < 0) {
            throw OverflowException.INSTANCE;
        }
        return r;
    }

    public static long addExact(long x, long y) throws OverflowException {
        long r = x + y;
        if (((x ^ r) & (y ^ r)) < 0) {
            throw OverflowException.INSTANCE;
        }
        return r;
    }

    public static int subtractExact(int x, int y) throws OverflowException {
        int r = x - y;
        if (((x ^ y) & (x ^ r)) < 0) {
            throw OverflowException.INSTANCE;
        }
        return r;
    }

    public static long subtractExact(long x, long y) throws OverflowException {
        long r = x - y;
        if (((x ^ y) & (x ^ r)) < 0) {
            throw OverflowException.INSTANCE;
        }
        return r;
    }

    public static int toIntExact(long x) throws OverflowException {
        int r = (int) x;
        if (r != x) {
            throw OverflowException.INSTANCE;
        }
        return r;
    }

    public static int multiplyExact(int x, int y) throws OverflowException {
        // copy&paste from Math.multiplyExact
        long r = (long) x * (long) y;
        if ((int) r != r) {
            throw OverflowException.INSTANCE;
        }
        return (int) r;
    }

    private static final MBeanServer SERVER;
    private static final String OPERATION_NAME = "gcRun";
    private static final Object[] PARAMS = new Object[]{null};
    private static final String[] SIGNATURE = new String[]{String[].class.getName()};
    private static final ObjectName OBJECT_NAME;

    static {
        if (ImageInfo.inImageCode()) {
            OBJECT_NAME = null;
            SERVER = null;
        } else {
            SERVER = ManagementFactory.getPlatformMBeanServer();
            ObjectName n;
            try {
                n = new ObjectName("com.sun.management:type=DiagnosticCommand");
            } catch (final MalformedObjectNameException e) {
                n = null;
            }
            OBJECT_NAME = n;
        }
    }

    /**
     * {@link System#gc()} does not force a GC, but the DiagnosticCommand "gcRun" does.
     */
    @TruffleBoundary
    public static void forceFullGC() {
        if (OBJECT_NAME != null && SERVER != null) {
            try {
                SERVER.invoke(OBJECT_NAME, OPERATION_NAME, PARAMS, SIGNATURE);
            } catch (InstanceNotFoundException | ReflectionException | MBeanException e) {
                // use fallback
            }
        }
        System.gc();
        Runtime.getRuntime().freeMemory();
    }

    /**
     * Get the existing or create a new {@link CallTarget} for the provided root node.
     */
    @TruffleBoundary
    public static RootCallTarget getOrCreateCallTarget(RootNode rootNode) {
        RootCallTarget ct = rootNode.getCallTarget();
        if (ct == null) {
            ct = Truffle.getRuntime().createCallTarget(rootNode);
        }
        return ct;
    }

    @TruffleBoundary
    public static String format(String fmt, Object... args) {
        return String.format(fmt, args);
    }

    @TruffleBoundary(allowInlining = true)
    public static String newString(byte[] bytes) {
        return new String(bytes);
    }

    @TruffleBoundary(allowInlining = true)
    public static String newString(char[] chars) {
        return new String(chars);
    }

    @TruffleBoundary(allowInlining = true)
    public static StringBuilder newStringBuilder() {
        return new StringBuilder();
    }

    @TruffleBoundary(allowInlining = true)
    public static StringBuilder newStringBuilder(String str) {
        return new StringBuilder(str);
    }

    @TruffleBoundary(allowInlining = true)
    public static StringBuilder newStringBuilder(int capacity) {
        return new StringBuilder(capacity);
    }

    @TruffleBoundary(allowInlining = true)
    public static String sbToString(StringBuilder sb) {
        return sb.toString();
    }

    @TruffleBoundary(allowInlining = true)
    public static StringBuilder append(StringBuilder sb, char c) {
        return sb.append(c);
    }

    @TruffleBoundary(allowInlining = true)
    public static StringBuilder append(StringBuilder sb, String s) {
        return sb.append(s);
    }

    @TruffleBoundary(allowInlining = true)
    public static StringBuilder appendCodePoint(StringBuilder sb, int codePoint) {
        return sb.appendCodePoint(codePoint);
    }
}
