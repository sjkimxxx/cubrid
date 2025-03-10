/*
 * Copyright (c) 2016 CUBRID Corporation.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * - Neither the name of the <ORGANIZATION> nor the names of its contributors
 *   may be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 * OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 *
 */

package com.cubrid.plcsql.predefined.sp;

import com.cubrid.jsp.Server;
import com.cubrid.jsp.SysParam;
import com.cubrid.jsp.value.DateTimeParser;
import com.cubrid.plcsql.builtin.DBMS_OUTPUT;
import com.cubrid.plcsql.compiler.CoercionScheme;
import com.cubrid.plcsql.compiler.SymbolStack;
import com.cubrid.plcsql.compiler.annotation.Operator;
import com.cubrid.plcsql.compiler.type.Type;
import com.cubrid.plcsql.predefined.PlcsqlRuntimeError;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Stack;
import java.util.regex.PatternSyntaxException;

public class SpLib {

    public static final Date ZERO_DATE = new Date(0 - 1900, 0 - 1, 0);
    public static final Timestamp ZERO_DATETIME = new Timestamp(0 - 1900, 0 - 1, 0, 0, 0, 0, 0);

    public static boolean isZeroTimestamp(Timestamp ts) {
        return (ts != null && (ts.equals(ZERO_TIMESTAMP) || ts.equals(ZERO_TIMESTAMP_2)));
    }

    public static boolean checkDate(Date d) {
        if (d == null) {
            return false;
        }

        if (d.equals(ZERO_DATE)) {
            return true;
        }

        return d.compareTo(MIN_DATE) >= 0 && d.compareTo(MAX_DATE) <= 0;
    }

    public static Timestamp checkTimestamp(Timestamp ts) {
        if (ts == null) {
            return null;
        }

        // '1970-01-01 00:00:00' (GMT) amounts to the Null Timestamp '0000-00-00 00:00:00' in CUBRID
        if (isZeroTimestamp(ts)) {
            return ZERO_TIMESTAMP;
        }

        if (ts.compareTo(MIN_TIMESTAMP) >= 0 && ts.compareTo(MAX_TIMESTAMP) <= 0) {
            return ts;
        } else {
            return null;
        }
    }

    public static boolean checkDatetime(Timestamp dt) {
        if (dt == null) {
            return false;
        }

        if (dt.equals(ZERO_DATETIME)) {
            return true;
        }

        return dt.compareTo(MIN_DATETIME) >= 0 && dt.compareTo(MAX_DATETIME) <= 0;
    }

    public static Float checkFloat(Float f) {

        assert f != null;

        if (f.isInfinite()) {
            throw new VALUE_ERROR("data overflow on data type FLOAT");
        }
        if (f.isNaN()) {
            throw new VALUE_ERROR("not a valid FLOAT value");
        }

        return f;
    }

    public static Double checkDouble(Double d) {

        assert d != null;

        if (d.isInfinite()) {
            throw new VALUE_ERROR("data overflow on data type DOUBLE");
        }
        if (d.isNaN()) {
            throw new VALUE_ERROR("not a valid DOUBLE value");
        }

        return d;
    }

    public static Timestamp parseTimestampStr(String s) {
        // parse again at runtime in order to use the runtime value of timezone setting
        ZonedDateTime timestamp = DateTimeParser.TimestampLiteral.parse(s);
        if (timestamp == null) {
            // The string was valid at the compile time (see
            // ParseTreeConverter.visitTimestamp_exp()).
            // But, this error can happen due to a timezone setting change after the compilation
            throw new VALUE_ERROR(String.format("invalid TIMESTAMP string: %s", s));
        }

        if (timestamp.equals(DateTimeParser.nullDatetimeGMT)) {
            return ZERO_TIMESTAMP;
        } else {
            return new Timestamp(timestamp.toEpochSecond() * 1000);
        }
    }

    public static Object getFieldWithIndex(ResultSet rs, int idx) throws SQLException {
        Object o = rs.getObject(idx);
        if (o != null && rs.wasNull()) {
            return null;
        } else {
            return o;
        }
    }

    public static Object getFieldWithName(ResultSet rs, String name) throws SQLException {
        Object o = rs.getObject(name);
        if (o != null && rs.wasNull()) {
            return null;
        } else {
            return o;
        }
    }

    public static String checkStrLength(boolean isChar, int length, String val) {

        if (val == null) {
            return null;
        }

        assert length >= 1;

        if (val.length() > length) {
            throw new VALUE_ERROR("string does not fit in the target type's length");
        }

        if (isChar) {
            int d = length - val.length();
            if (d > 0) {

                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < d; i++) {
                    sb.append(" ");
                }
                val = val + sb.toString();
            }
        }

        return val;
    }

    public static BigDecimal checkPrecision(int prec, short scale, BigDecimal val) {

        if (val == null) {
            return null;
        }

        // ParseTreeConverter.visitNumeric_type() guarantees the following assertions
        assert prec >= 1 && prec <= 38;
        assert scale >= 0 && scale <= prec;

        if (val.scale() != scale) {
            val = val.setScale(scale, RoundingMode.HALF_UP);
        }

        if (val.precision() > prec) {
            throw new VALUE_ERROR(
                    "numeric value does not fit in the target type's precision and scale");
        }

        return val;
    }

    // -------------------------------------------------------------------------------
    // To provide line and column numbers for run-time exceptions
    //

    public static int[] getPlcLineColumn(
            List<CodeRangeMarker> crmList, Throwable thrown, String fileName) {

        StackTraceElement[] stackTrace = thrown.getStackTrace();

        // get exception line number in the generated Java class
        int exceptionJavaLine = 0;
        for (StackTraceElement e : stackTrace) {
            if (e.getFileName().equals(fileName)) {
                exceptionJavaLine = e.getLineNumber();
                break;
            }
        }
        if (exceptionJavaLine == 0) {
            return UNKNOWN_LINE_COLUMN;
        }

        // find the innermost code range that contains the Java line number
        Stack<CodeRangeMarker> stack = new Stack<>();
        for (CodeRangeMarker crm : crmList) {

            if (exceptionJavaLine < crm.javaLine) {
                CodeRangeMarker innermost = stack.peek();
                assert innermost != null;
                return new int[] {innermost.plcLine, innermost.plcColumn};
            }

            if (crm.isBegin) {
                stack.push(crm);
            } else {
                stack.pop();
            }
        }
        throw new PROGRAM_ERROR(); // unreachable
    }

    public static List<CodeRangeMarker> buildCodeRangeMarkerList(String markers) {

        String[] split = markers.split(" ");
        assert split[0].length() == 0 && split[1].charAt(0) == '(';

        List<CodeRangeMarker> ret = new LinkedList<>();

        int stackHeight = 0; // to check the validity of generated code range markers
        int len = split.length;
        for (int i = 1; i < len; i++) {

            String s = split[i];

            boolean isBegin = (s.charAt(0) == '(');
            assert isBegin || (s.charAt(0) == ')');
            if (isBegin) {
                // beginning marker of the form '(<java-line>,<plc-line>,<plc-column>'
                stackHeight++;
                String[] split2 = s.substring(1).split(",");
                assert split2.length == 3;
                ret.add(
                        new CodeRangeMarker(
                                true,
                                Integer.parseInt(split2[0]),
                                Integer.parseInt(split2[1]),
                                Integer.parseInt(split2[2])));
            } else {
                // ending marker of the form ')<java-line>'
                stackHeight--;
                ret.add(new CodeRangeMarker(false, Integer.parseInt(s.substring(1)), -1, -1));
            }
        }
        assert stackHeight == 0;

        return ret;
    }

    public static class CodeRangeMarker {

        public final boolean isBegin;
        public final int javaLine;
        public final int plcLine;
        public final int plcColumn;

        public CodeRangeMarker(boolean isBegin, int javaLine, int plcLine, int plcColumn) {
            this.isBegin = isBegin;
            this.javaLine = javaLine;
            this.plcLine = plcLine;
            this.plcColumn = plcColumn;
        }
    }

    //
    // To provide line and column numbers for run-time exceptions
    // -------------------------------------------------------------------------------

    public static Object invokeBuiltinFunc(
            Connection conn, String name, int resultTypeCode, Object... args) {

        assert args != null;

        int argsLen = args.length;
        String hostVars;
        if (SymbolStack.noParenBuiltInFunc.indexOf(name) >= 0) {
            assert argsLen == 0;
            hostVars = "";
        } else {
            hostVars = getHostVarsStr(argsLen);
        }
        String query = String.format("select %s%s from dual", name, hostVars);
        try {
            PreparedStatement pstmt = conn.prepareStatement(query);
            for (int i = 0; i < argsLen; i++) {
                pstmt.setObject(i + 1, args[i]);
            }
            ResultSet rs = pstmt.executeQuery();
            if (rs.next()) {
                Object ret;
                switch (resultTypeCode) {
                    case Type.IDX_NULL:
                    case Type.IDX_OBJECT:
                        ret = rs.getObject(1);
                        break;
                    case Type.IDX_STRING:
                        ret = rs.getString(1);
                        break;
                    case Type.IDX_SHORT:
                        ret = rs.getShort(1);
                        break;
                    case Type.IDX_INT:
                        ret = rs.getInt(1);
                        break;
                    case Type.IDX_BIGINT:
                        ret = rs.getLong(1);
                        break;
                    case Type.IDX_NUMERIC:
                        ret = rs.getBigDecimal(1);
                        break;
                    case Type.IDX_FLOAT:
                        ret = rs.getFloat(1);
                        break;
                    case Type.IDX_DOUBLE:
                        ret = rs.getDouble(1);
                        break;
                    case Type.IDX_DATE:
                        ret = rs.getDate(1);
                        break;
                    case Type.IDX_TIME:
                        ret = rs.getTime(1);
                        break;
                    case Type.IDX_DATETIME:
                    case Type.IDX_TIMESTAMP:
                        ret = rs.getTimestamp(1);
                        break;
                    default:
                        throw new PROGRAM_ERROR(); // unreachable
                }
                assert !rs.next(); // it must have only one record
                if (ret != null && rs.wasNull()) {
                    ret = null;
                }

                Statement stmt = rs.getStatement();
                if (stmt != null) {
                    stmt.close();
                }

                return ret;
            } else {
                throw new PROGRAM_ERROR(); // unreachable
            }
        } catch (SQLException e) {
            Server.log(e);
            throw new SQL_ERROR(e.getMessage());
        }
    }

    public static Object throwInvalidCursor(String msg) {
        throw new INVALID_CURSOR(msg);
    }

    // ---------------------------------------------------------------------------------------
    // various check functions
    //

    public static <T> T checkNotNull(T val, String errMsg) {
        if (val == null) {
            throw new VALUE_ERROR(errMsg);
        }

        return val;
    }

    public static Integer checkForLoopIterStep(Integer step) {
        if (step <= 0) {
            throw new VALUE_ERROR("FOR loop iteration steps must be positive integers");
        }

        return step;
    }

    // ---------------------------------------------------------------------------------------
    // builtin exceptions
    //
    public static class CASE_NOT_FOUND extends PlcsqlRuntimeError {
        public CASE_NOT_FOUND() {
            super(CODE_CASE_NOT_FOUND, MSG_CASE_NOT_FOUND);
        }

        public CASE_NOT_FOUND(String msg) {
            super(CODE_CASE_NOT_FOUND, isEmptyStr(msg) ? MSG_CASE_NOT_FOUND : msg);
        }
    }

    public static class CURSOR_ALREADY_OPEN extends PlcsqlRuntimeError {
        public CURSOR_ALREADY_OPEN() {
            super(CODE_CURSOR_ALREADY_OPEN, MSG_CURSOR_ALREADY_OPEN);
        }

        public CURSOR_ALREADY_OPEN(String msg) {
            super(CODE_CURSOR_ALREADY_OPEN, isEmptyStr(msg) ? MSG_CURSOR_ALREADY_OPEN : msg);
        }
    }

    public static class INVALID_CURSOR extends PlcsqlRuntimeError {
        public INVALID_CURSOR() {
            super(CODE_INVALID_CURSOR, MSG_INVALID_CURSOR);
        }

        public INVALID_CURSOR(String msg) {
            super(CODE_INVALID_CURSOR, isEmptyStr(msg) ? MSG_INVALID_CURSOR : msg);
        }
    }

    public static class NO_DATA_FOUND extends PlcsqlRuntimeError {
        public NO_DATA_FOUND() {
            super(CODE_NO_DATA_FOUND, MSG_NO_DATA_FOUND);
        }

        public NO_DATA_FOUND(String msg) {
            super(CODE_NO_DATA_FOUND, isEmptyStr(msg) ? MSG_NO_DATA_FOUND : msg);
        }
    }

    public static class PROGRAM_ERROR extends PlcsqlRuntimeError {
        public PROGRAM_ERROR() {
            super(CODE_PROGRAM_ERROR, MSG_PROGRAM_ERROR);
        }

        public PROGRAM_ERROR(String msg) {
            super(CODE_PROGRAM_ERROR, isEmptyStr(msg) ? MSG_PROGRAM_ERROR : msg);
        }
    }

    public static class STORAGE_ERROR extends PlcsqlRuntimeError {
        public STORAGE_ERROR() {
            super(CODE_STORAGE_ERROR, MSG_STORAGE_ERROR);
        }

        public STORAGE_ERROR(String msg) {
            super(CODE_STORAGE_ERROR, isEmptyStr(msg) ? MSG_STORAGE_ERROR : msg);
        }
    }

    public static class SQL_ERROR extends PlcsqlRuntimeError {
        public SQL_ERROR() {
            super(CODE_STORAGE_ERROR, MSG_SQL_ERROR);
        }

        public SQL_ERROR(String msg) {
            super(CODE_STORAGE_ERROR, isEmptyStr(msg) ? MSG_SQL_ERROR : msg);
        }
    }

    public static class TOO_MANY_ROWS extends PlcsqlRuntimeError {
        public TOO_MANY_ROWS() {
            super(CODE_TOO_MANY_ROWS, MSG_TOO_MANY_ROWS);
        }

        public TOO_MANY_ROWS(String msg) {
            super(CODE_TOO_MANY_ROWS, isEmptyStr(msg) ? MSG_TOO_MANY_ROWS : msg);
        }
    }

    public static class VALUE_ERROR extends PlcsqlRuntimeError {
        public VALUE_ERROR() {
            super(CODE_VALUE_ERROR, MSG_VALUE_ERROR);
        }

        public VALUE_ERROR(String msg) {
            super(CODE_VALUE_ERROR, isEmptyStr(msg) ? MSG_VALUE_ERROR : msg);
        }
    }

    public static class ZERO_DIVIDE extends PlcsqlRuntimeError {
        public ZERO_DIVIDE() {
            super(CODE_ZERO_DIVIDE, MSG_ZERO_DIVIDE);
        }

        public ZERO_DIVIDE(String msg) {
            super(CODE_ZERO_DIVIDE, isEmptyStr(msg) ? MSG_ZERO_DIVIDE : msg);
        }
    }

    //
    // builtin exceptions
    // ---------------------------------------------------------------------------------------

    private static int checkAppErrCode(int code) {
        if (code <= CODE_APP_ERROR) {
            throw new VALUE_ERROR(
                    "exception codes below " + (CODE_APP_ERROR + 1) + " are reserved");
        }

        return code;
    }

    // user defined exception
    public static class $APP_ERROR extends PlcsqlRuntimeError {
        public $APP_ERROR(int code, String msg) {
            // called for raise_application_error(...)
            super(checkAppErrCode(code), isEmptyStr(msg) ? MSG_APP_ERROR : msg);
        }

        public $APP_ERROR() {
            // called for user defined exceptions
            super(CODE_APP_ERROR, MSG_APP_ERROR);
        }
    }

    // --------------------------------------------------------
    // DBMS_OUTPUT procedures

    public static void DBMS_OUTPUT$DISABLE() {
        DBMS_OUTPUT.disable();
    }

    public static void DBMS_OUTPUT$ENABLE(Integer size) {
        if (size == null) {
            throw new VALUE_ERROR("size must be non-null");
        }
        DBMS_OUTPUT.enable(size);
    }

    public static void DBMS_OUTPUT$GET_LINE(String[] line, Integer[] status) {
        int[] iArr = new int[1];
        DBMS_OUTPUT.getLine(line, iArr);
        status[0] = iArr[0];
    }

    public static void DBMS_OUTPUT$NEW_LINE() {
        DBMS_OUTPUT.newLine();
    }

    public static void DBMS_OUTPUT$PUT_LINE(String s) {
        DBMS_OUTPUT.putLine(s);
    }

    public static void DBMS_OUTPUT$PUT(String s) {
        DBMS_OUTPUT.put(s);
    }

    // --------------------------------------------------------

    public static class Query {
        public final String query;
        public ResultSet rs;
        public int rowCount;

        public Query(String query) {
            this.query = query;
        }

        public void open(Connection conn, Object... val) {

            assert val != null;

            try {
                if (isOpen()) {
                    throw new CURSOR_ALREADY_OPEN();
                }
                PreparedStatement pstmt = conn.prepareStatement(query);
                for (int i = 0; i < val.length; i++) {
                    pstmt.setObject(i + 1, val[i]);
                }
                rs = pstmt.executeQuery();
            } catch (SQLException e) {
                Server.log(e);
                throw new SQL_ERROR(e.getMessage());
            }
        }

        public void close() {
            try {
                if (!isOpen()) {
                    throw new INVALID_CURSOR("attempted to close an unopened cursor");
                }
                if (rs != null) {
                    Statement stmt = rs.getStatement();
                    if (stmt != null) {
                        stmt.close();
                    }
                    rs = null;
                }
            } catch (SQLException e) {
                Server.log(e);
                throw new SQL_ERROR(e.getMessage());
            }
        }

        public boolean isOpen() {
            try {
                return (rs != null && !rs.isClosed());
            } catch (SQLException e) {
                Server.log(e);
                throw new SQL_ERROR(e.getMessage());
            }
        }

        public boolean found() {
            try {
                if (!isOpen()) {
                    throw new INVALID_CURSOR(
                            "attempted to read an attribute of an unopened cursor");
                }
                return rs.getRow() > 0;
            } catch (SQLException e) {
                Server.log(e);
                throw new SQL_ERROR(e.getMessage());
            }
        }

        public boolean notFound() {
            try {
                if (!isOpen()) {
                    throw new INVALID_CURSOR(
                            "attempted to read an attribute of an unopened cursor");
                }
                return rs.getRow() == 0;
            } catch (SQLException e) {
                Server.log(e);
                throw new SQL_ERROR(e.getMessage());
            }
        }

        public long rowCount() {
            if (!isOpen()) {
                throw new INVALID_CURSOR("attempted to read an attribute of an unopened cursor");
            }
            return (long) rowCount;
        }

        public void updateRowCount() {
            try {
                rowCount = rs.getRow();
            } catch (SQLException e) {
                Server.log(e);
                throw new SQL_ERROR(e.getMessage());
            }
        }
    }

    // ------------------------------------
    // operators
    // ------------------------------------

    // ====================================
    // boolean not
    @Operator(coercionScheme = CoercionScheme.LogicalOp)
    public static Boolean opNot(Boolean l) {
        if (l == null) {
            return null;
        }
        return !l;
    }

    // ====================================
    // is null
    @Operator(coercionScheme = CoercionScheme.ObjectOp)
    public static Boolean opIsNull(Object l) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
        }

        return (l == null);
    }

    // ====================================
    // arithmetic negative
    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Short opNeg(Short l) {
        if (l == null) {
            return null;
        }
        return negateShortExact(l);
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Integer opNeg(Integer l) {
        if (l == null) {
            return null;
        }
        try {
            return Math.negateExact(l);
        } catch (ArithmeticException e) {
            throw new VALUE_ERROR("data overflow in negation of an INTEGER value");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Long opNeg(Long l) {
        if (l == null) {
            return null;
        }
        try {
            return Math.negateExact(l);
        } catch (ArithmeticException e) {
            throw new VALUE_ERROR("data overflow in negation of a BIGINT value");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static BigDecimal opNeg(BigDecimal l) {
        if (l == null) {
            return null;
        }
        return l.negate();
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Float opNeg(Float l) {
        if (l == null) {
            return null;
        }
        try {
            return checkFloat(-l);
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow in negation of a FLOAT value");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Double opNeg(Double l) {
        if (l == null) {
            return null;
        }
        try {
            return checkDouble(-l);
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow in negation of a DOUBLE value");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Object opNeg(Object l) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
        }

        if (l == null) {
            return null;
        }

        if (l instanceof Boolean) {
            // not applicable
        } else if (l instanceof String) {
            // double
            return opNeg(convStringToDouble((String) l));
        } else if (l instanceof Short) {
            // short
            return opNeg((Short) l);
        } else if (l instanceof Integer) {
            // int
            return opNeg((Integer) l);
        } else if (l instanceof Long) {
            // bigint
            return opNeg((Long) l);
        } else if (l instanceof BigDecimal) {
            // numeric
            return opNeg((BigDecimal) l);
        } else if (l instanceof Float) {
            // float
            return opNeg((Float) l);
        } else if (l instanceof Double) {
            // double
            return opNeg((Double) l);
        } else if (l instanceof Date) {
            // not applicable
        } else if (l instanceof Time) {
            // not applicable
        } else if (l instanceof Timestamp) {
            throw new PROGRAM_ERROR("operand's type is ambiguous: TIMESTAMP or DATETIME");
        }

        throw new VALUE_ERROR(
                String.format(
                        "cannot negate the argument due to its incompatible run-time type %s",
                        plcsqlTypeOfJavaObject(l)));
    }

    // ====================================
    // bitwise compliment
    @Operator(coercionScheme = CoercionScheme.IntArithOp)
    public static Long opBitCompli(Short l) {
        if (l == null) {
            return null;
        }
        return ~l.longValue();
    }

    @Operator(coercionScheme = CoercionScheme.IntArithOp)
    public static Long opBitCompli(Integer l) {
        if (l == null) {
            return null;
        }
        return ~l.longValue();
    }

    @Operator(coercionScheme = CoercionScheme.IntArithOp)
    public static Long opBitCompli(Long l) {
        if (l == null) {
            return null;
        }
        return ~l;
    }

    @Operator(coercionScheme = CoercionScheme.IntArithOp)
    public static Object opBitCompli(Object l) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
        }

        if (l == null) {
            return null;
        }

        if (l instanceof Boolean) {
            // not applicable
        } else if (l instanceof String) {
            // bigint
            return opBitCompli(convStringToBigint((String) l));
        } else if (l instanceof Short) {
            // short
            return opBitCompli((Short) l);
        } else if (l instanceof Integer) {
            // int
            return opBitCompli((Integer) l);
        } else if (l instanceof Long) {
            // bigint
            return opBitCompli((Long) l);
        } else if (l instanceof BigDecimal) {
            // bigint
            return opBitCompli(convNumericToBigint((BigDecimal) l));
        } else if (l instanceof Float) {
            // bigint
            return opBitCompli(convFloatToBigint((Float) l));
        } else if (l instanceof Double) {
            // bigint
            return opBitCompli(convDoubleToBigint((Double) l));
        } else if (l instanceof Date) {
            // not applicable
        } else if (l instanceof Time) {
            // not applicable
        } else if (l instanceof Timestamp) {
            throw new PROGRAM_ERROR("operand's type is ambiguous: TIMESTAMP or DATETIME");
        }

        throw new VALUE_ERROR(
                String.format(
                        "cannot take bit-compliment of the argument due to its incompatible run-time type %s",
                        plcsqlTypeOfJavaObject(l)));
    }

    // ====================================
    // boolean and
    @Operator(coercionScheme = CoercionScheme.LogicalOp)
    public static Boolean opAnd(Boolean l, Boolean r) {
        if (l == null || r == null) {
            return null;
        }
        return l && r;
    }

    // ====================================
    // boolean or
    @Operator(coercionScheme = CoercionScheme.LogicalOp)
    public static Boolean opOr(Boolean l, Boolean r) {
        if (l == null || r == null) {
            return null;
        }
        return l || r;
    }

    // ====================================
    // boolean xor
    @Operator(coercionScheme = CoercionScheme.LogicalOp)
    public static Boolean opXor(Boolean l, Boolean r) {
        if (l == null || r == null) {
            return null;
        }
        return (l && !r) || (!l && r);
    }

    // ====================================
    // comparison equal

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opEq(Boolean l, Boolean r) {
        return commonOpEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opEq(String l, String r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        return commonOpEq(l, r);
    }

    public static Boolean opEqChar(String l, String r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }

        l = rtrim(l);
        r = rtrim(r);

        return l.equals(r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opEq(BigDecimal l, BigDecimal r) {
        return commonOpEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opEq(Short l, Short r) {
        return commonOpEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opEq(Integer l, Integer r) {
        return commonOpEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opEq(Long l, Long r) {
        return commonOpEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opEq(Float l, Float r) {
        return commonOpEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opEq(Double l, Double r) {
        return commonOpEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opEq(Time l, Time r) {
        return commonOpEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opEq(Date l, Date r) {
        return commonOpEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opEq(Timestamp l, Timestamp r) {
        return commonOpEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opEq(ZonedDateTime l, ZonedDateTime r) {
        // cannot be called actually, but only to register this operator with a parameter type
        // TIMESTAMP
        throw new PROGRAM_ERROR(); // unreachable
    }

    public static Boolean opEqTimestamp(Timestamp l, Timestamp r) {
        if (l == null || r == null) {
            return null;
        }
        assert l.getNanos() == 0;
        assert r.getNanos() == 0;

        return l.equals(r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opEq(Object l, Object r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }
        return compareWithRuntimeTypeConv(l, r) == 0;
    }

    // ====================================
    // comparison null safe equal

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNullSafeEq(Boolean l, Boolean r) {
        return commonOpNullSafeEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNullSafeEq(String l, String r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        return commonOpNullSafeEq(l, r);
    }

    public static Boolean opNullSafeEqChar(String l, String r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null) {
            return (r == null);
        } else if (r == null) {
            return false;
        }

        l = rtrim(l);
        r = rtrim(r);

        return l.equals(r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNullSafeEq(BigDecimal l, BigDecimal r) {
        return commonOpNullSafeEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNullSafeEq(Short l, Short r) {
        return commonOpNullSafeEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNullSafeEq(Integer l, Integer r) {
        return commonOpNullSafeEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNullSafeEq(Long l, Long r) {
        return commonOpNullSafeEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNullSafeEq(Float l, Float r) {
        return commonOpNullSafeEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNullSafeEq(Double l, Double r) {
        return commonOpNullSafeEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNullSafeEq(Time l, Time r) {
        return commonOpNullSafeEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNullSafeEq(Date l, Date r) {
        return commonOpNullSafeEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNullSafeEq(Timestamp l, Timestamp r) {
        return commonOpNullSafeEq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNullSafeEq(ZonedDateTime l, ZonedDateTime r) {
        // cannot be called actually, but only to register this operator with a parameter type
        // TIMESTAMP
        throw new PROGRAM_ERROR(); // unreachable
    }

    public static Boolean opNullSafeEqTimestamp(Timestamp l, Timestamp r) {
        if (l == null) {
            if (r == null) {
                return true;
            } else {
                assert r.getNanos() == 0;
                return false;
            }
        } else {
            assert l.getNanos() == 0;

            if (r == null) {
                return false;
            } else {
                assert r.getNanos() == 0;
                return l.equals(r);
            }
        }
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNullSafeEq(Object l, Object r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null) {
            return (r == null);
        } else if (r == null) {
            return false;
        }

        return compareWithRuntimeTypeConv(l, r) == 0;
    }

    // ====================================
    // comparison not equal

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNeq(Boolean l, Boolean r) {
        return commonOpNeq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNeq(String l, String r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        return commonOpNeq(l, r);
    }

    public static Boolean opNeqChar(String l, String r) {
        if (l == null || r == null) {
            return null;
        }

        l = rtrim(l);
        r = rtrim(r);

        return !l.equals(r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNeq(BigDecimal l, BigDecimal r) {
        return commonOpNeq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNeq(Short l, Short r) {
        return commonOpNeq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNeq(Integer l, Integer r) {
        return commonOpNeq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNeq(Long l, Long r) {
        return commonOpNeq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNeq(Float l, Float r) {
        return commonOpNeq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNeq(Double l, Double r) {
        return commonOpNeq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNeq(Time l, Time r) {
        return commonOpNeq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNeq(Date l, Date r) {
        return commonOpNeq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNeq(Timestamp l, Timestamp r) {
        return commonOpNeq(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNeq(ZonedDateTime l, ZonedDateTime r) {
        // cannot be called actually, but only to register this operator with a parameter type
        // TIMESTAMP
        throw new PROGRAM_ERROR(); // unreachable
    }

    public static Boolean opNeqTimestamp(Timestamp l, Timestamp r) {
        if (l == null || r == null) {
            return null;
        }
        assert l.getNanos() == 0;
        assert r.getNanos() == 0;

        return !l.equals(r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opNeq(Object l, Object r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }

        return compareWithRuntimeTypeConv(l, r) != 0;
    }

    // ====================================
    // comparison less than or equal to (<=)

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLe(Boolean l, Boolean r) {
        return commonOpLe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLe(String l, String r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        return commonOpLe(l, r);
    }

    public static Boolean opLeChar(String l, String r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }

        l = rtrim(l);
        r = rtrim(r);

        return l.compareTo(r) <= 0;
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLe(Short l, Short r) {
        return commonOpLe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLe(Integer l, Integer r) {
        return commonOpLe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLe(Long l, Long r) {
        return commonOpLe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLe(BigDecimal l, BigDecimal r) {
        return commonOpLe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLe(Float l, Float r) {
        return commonOpLe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLe(Double l, Double r) {
        return commonOpLe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLe(Date l, Date r) {
        return commonOpLe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLe(Time l, Time r) {
        return commonOpLe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLe(Timestamp l, Timestamp r) {
        return commonOpLe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLe(ZonedDateTime l, ZonedDateTime r) {
        // cannot be called actually, but only to register this operator with a parameter type
        // TIMESTAMP
        throw new PROGRAM_ERROR(); // unreachable
    }

    public static Boolean opLeTimestamp(Timestamp l, Timestamp r) {
        if (l == null || r == null) {
            return null;
        }
        assert l.getNanos() == 0;
        assert r.getNanos() == 0;

        return l.compareTo(r) <= 0;
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLe(Object l, Object r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }
        return compareWithRuntimeTypeConv(l, r) <= 0;
    }

    // ====================================
    // comparison greater than or equal to (>=)
    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGe(Boolean l, Boolean r) {
        return commonOpGe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGe(String l, String r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        return commonOpGe(l, r);
    }

    public static Boolean opGeChar(String l, String r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }

        l = rtrim(l);
        r = rtrim(r);

        return l.compareTo(r) >= 0;
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGe(Short l, Short r) {
        return commonOpGe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGe(Integer l, Integer r) {
        return commonOpGe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGe(Long l, Long r) {
        return commonOpGe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGe(BigDecimal l, BigDecimal r) {
        return commonOpGe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGe(Float l, Float r) {
        return commonOpGe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGe(Double l, Double r) {
        return commonOpGe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGe(Date l, Date r) {
        return commonOpGe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGe(Time l, Time r) {
        return commonOpGe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGe(Timestamp l, Timestamp r) {
        return commonOpGe(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGe(ZonedDateTime l, ZonedDateTime r) {
        // cannot be called actually, but only to register this operator with a parameter type
        // TIMESTAMP
        throw new PROGRAM_ERROR(); // unreachable
    }

    public static Boolean opGeTimestamp(Timestamp l, Timestamp r) {
        if (l == null || r == null) {
            return null;
        }
        assert l.getNanos() == 0;
        assert r.getNanos() == 0;

        return l.compareTo(r) >= 0;
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGe(Object l, Object r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }
        return compareWithRuntimeTypeConv(l, r) >= 0;
    }

    // ====================================
    // comparison less than (<)
    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLt(Boolean l, Boolean r) {
        return commonOpLt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLt(String l, String r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        return commonOpLt(l, r);
    }

    public static Boolean opLtChar(String l, String r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }

        l = rtrim(l);
        r = rtrim(r);

        return l.compareTo(r) < 0;
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLt(Short l, Short r) {
        return commonOpLt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLt(Integer l, Integer r) {
        return commonOpLt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLt(Long l, Long r) {
        return commonOpLt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLt(BigDecimal l, BigDecimal r) {
        return commonOpLt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLt(Float l, Float r) {
        return commonOpLt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLt(Double l, Double r) {
        return commonOpLt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLt(Date l, Date r) {
        return commonOpLt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLt(Time l, Time r) {
        return commonOpLt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLt(Timestamp l, Timestamp r) {
        return commonOpLt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLt(ZonedDateTime l, ZonedDateTime r) {
        // cannot be called actually, but only to register this operator with a parameter type
        // TIMESTAMP
        throw new PROGRAM_ERROR(); // unreachable
    }

    public static Boolean opLtTimestamp(Timestamp l, Timestamp r) {
        if (l == null || r == null) {
            return null;
        }
        assert l.getNanos() == 0;
        assert r.getNanos() == 0;

        return l.compareTo(r) < 0;
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opLt(Object l, Object r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }

        return compareWithRuntimeTypeConv(l, r) < 0;
    }

    // ====================================
    // comparison greater than (>)
    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGt(Boolean l, Boolean r) {
        return commonOpGt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGt(String l, String r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        return commonOpGt(l, r);
    }

    public static Boolean opGtChar(String l, String r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }

        l = rtrim(l);
        r = rtrim(r);

        return l.compareTo(r) > 0;
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGt(Short l, Short r) {
        return commonOpGt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGt(Integer l, Integer r) {
        return commonOpGt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGt(Long l, Long r) {
        return commonOpGt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGt(BigDecimal l, BigDecimal r) {
        return commonOpGt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGt(Float l, Float r) {
        return commonOpGt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGt(Double l, Double r) {
        return commonOpGt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGt(Date l, Date r) {
        return commonOpGt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGt(Time l, Time r) {
        return commonOpGt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGt(Timestamp l, Timestamp r) {
        return commonOpGt(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGt(ZonedDateTime l, ZonedDateTime r) {
        // cannot be called actually, but only to register this operator with a parameter type
        // TIMESTAMP
        throw new PROGRAM_ERROR(); // unreachable
    }

    public static Boolean opGtTimestamp(Timestamp l, Timestamp r) {
        if (l == null || r == null) {
            return null;
        }
        assert l.getNanos() == 0;
        assert r.getNanos() == 0;

        return l.compareTo(r) > 0;
    }

    @Operator(coercionScheme = CoercionScheme.CompOp)
    public static Boolean opGt(Object l, Object r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }

        return compareWithRuntimeTypeConv(l, r) > 0;
    }

    // ====================================
    // between
    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opBetween(Boolean o, Boolean lower, Boolean upper) {
        if (o == null || lower == null || upper == null) {
            return null;
        }
        return o.compareTo(lower) >= 0 && o.compareTo(upper) <= 0;
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opBetween(String o, String lower, String upper) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(o)) {
                o = null;
            }
            if (EMPTY_STRING.equals(lower)) {
                lower = null;
            }
            if (EMPTY_STRING.equals(upper)) {
                upper = null;
            }
        }

        if (o == null || lower == null || upper == null) {
            return null;
        }
        return o.compareTo(lower) >= 0 && o.compareTo(upper) <= 0;
    }

    public static Boolean opBetweenChar(String o, String lower, String upper) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(o)) {
                o = null;
            }
            if (EMPTY_STRING.equals(lower)) {
                lower = null;
            }
            if (EMPTY_STRING.equals(upper)) {
                upper = null;
            }
        }

        if (o == null || lower == null || upper == null) {
            return null;
        }

        o = rtrim(o);
        lower = rtrim(lower);
        upper = rtrim(upper);

        return o.compareTo(lower) >= 0 && o.compareTo(upper) <= 0;
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opBetween(Short o, Short lower, Short upper) {
        if (o == null || lower == null || upper == null) {
            return null;
        }
        return o >= lower && o <= upper;
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opBetween(Integer o, Integer lower, Integer upper) {
        if (o == null || lower == null || upper == null) {
            return null;
        }
        return o >= lower && o <= upper;
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opBetween(Long o, Long lower, Long upper) {
        if (o == null || lower == null || upper == null) {
            return null;
        }
        return o >= lower && o <= upper;
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opBetween(BigDecimal o, BigDecimal lower, BigDecimal upper) {
        if (o == null || lower == null || upper == null) {
            return null;
        }
        return o.compareTo(lower) >= 0 && o.compareTo(upper) <= 0;
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opBetween(Float o, Float lower, Float upper) {
        if (o == null || lower == null || upper == null) {
            return null;
        }
        return o >= lower && o <= upper;
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opBetween(Double o, Double lower, Double upper) {
        if (o == null || lower == null || upper == null) {
            return null;
        }
        return o >= lower && o <= upper;
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opBetween(Date o, Date lower, Date upper) {
        if (o == null || lower == null || upper == null) {
            return null;
        }
        return o.compareTo(lower) >= 0 && o.compareTo(upper) <= 0;
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opBetween(Time o, Time lower, Time upper) {
        if (o == null || lower == null || upper == null) {
            return null;
        }
        return o.compareTo(lower) >= 0 && o.compareTo(upper) <= 0;
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opBetween(Timestamp o, Timestamp lower, Timestamp upper) {
        if (o == null || lower == null || upper == null) {
            return null;
        }
        return o.compareTo(lower) >= 0 && o.compareTo(upper) <= 0;
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opBetween(ZonedDateTime o, ZonedDateTime lower, ZonedDateTime upper) {
        // cannot be called actually, but only to register this operator with a parameter type
        // TIMESTAMP
        throw new PROGRAM_ERROR(); // unreachable
    }

    public static Boolean opBetweenTimestamp(Timestamp o, Timestamp lower, Timestamp upper) {
        if (o == null || lower == null || upper == null) {
            return null;
        }
        assert o.getNanos() == 0;
        assert lower.getNanos() == 0;
        assert upper.getNanos() == 0;

        return o.compareTo(lower) >= 0 && o.compareTo(upper) <= 0;
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opBetween(Object o, Object lower, Object upper) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(o)) {
                o = null;
            }
            if (EMPTY_STRING.equals(lower)) {
                lower = null;
            }
            if (EMPTY_STRING.equals(upper)) {
                upper = null;
            }
        }

        if (o == null || lower == null || upper == null) {
            return null;
        }

        return compareWithRuntimeTypeConv(lower, o) <= 0
                && compareWithRuntimeTypeConv(o, upper) <= 0;
    }

    // ====================================
    // in

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opIn(Boolean o, Boolean... arr) {
        assert arr != null;
        return commonOpIn(o, (Object[]) arr);
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opIn(String o, String... arr) {
        assert arr != null;
        return commonOpIn(o, (Object[]) arr);
    }

    public static Boolean opInChar(String o, String... arr) {
        assert arr != null;

        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(o)) {
                o = null;
            }
        }

        if (o == null) {
            return null;
        }
        o = rtrim(o);

        boolean nullFound = false;
        for (String p : arr) {
            if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
                if (EMPTY_STRING.equals(p)) {
                    p = null;
                }
            }

            if (p == null) {
                nullFound = true;
            } else {
                p = rtrim(p);
                if (o.equals(p)) {
                    return true;
                }
            }
        }
        return nullFound ? null : false;
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opIn(BigDecimal o, BigDecimal... arr) {
        assert arr != null;
        return commonOpIn(o, (Object[]) arr);
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opIn(Short o, Short... arr) {
        assert arr != null;
        return commonOpIn(o, (Object[]) arr);
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opIn(Integer o, Integer... arr) {
        assert arr != null;
        return commonOpIn(o, (Object[]) arr);
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opIn(Long o, Long... arr) {
        assert arr != null;
        return commonOpIn(o, (Object[]) arr);
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opIn(Float o, Float... arr) {
        assert arr != null;
        return commonOpIn(o, (Object[]) arr);
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opIn(Double o, Double... arr) {
        assert arr != null;
        return commonOpIn(o, (Object[]) arr);
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opIn(Date o, Date... arr) {
        assert arr != null;
        return commonOpIn(o, (Object[]) arr);
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opIn(Time o, Time... arr) {
        assert arr != null;
        return commonOpIn(o, (Object[]) arr);
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opIn(Timestamp o, Timestamp... arr) {
        assert arr != null;
        return commonOpIn(o, (Object[]) arr);
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opIn(ZonedDateTime o, ZonedDateTime... arr) {
        // cannot be called actually, but only to register this operator with a parameter type
        // TIMESTAMP
        throw new PROGRAM_ERROR(); // unreachable
    }

    public static Boolean opInTimestamp(Timestamp o, Timestamp... arr) {
        assert arr != null;

        if (o == null) {
            return null;
        }
        assert o.getNanos() == 0;

        boolean nullFound = false;
        for (Timestamp p : arr) {
            if (p == null) {
                nullFound = true;
            } else {
                assert p.getNanos() == 0;
                if (o.equals(p)) {
                    return true;
                }
            }
        }
        return nullFound ? null : false;
    }

    @Operator(coercionScheme = CoercionScheme.NAryCompOp)
    public static Boolean opIn(Object o, Object... arr) {
        assert arr != null;

        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(o)) {
                o = null;
            }
        }

        if (o == null) {
            return null;
        }
        boolean nullFound = false;
        for (Object p : arr) {
            if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
                if (EMPTY_STRING.equals(p)) {
                    p = null;
                }
            }

            if (p == null) {
                nullFound = true;
            } else {
                if (compareWithRuntimeTypeConv(o, p) == 0) {
                    return true;
                }
            }
        }
        return nullFound ? null : false;
    }
    // ====================================
    // *
    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Short opMult(Short l, Short r) {
        if (l == null || r == null) {
            return null;
        }
        return multiplyShortExact(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Integer opMult(Integer l, Integer r) {
        if (l == null || r == null) {
            return null;
        }
        try {
            return Math.multiplyExact(l, r);
        } catch (ArithmeticException e) {
            throw new VALUE_ERROR("data overflow in multiplication of INTEGER values");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Long opMult(Long l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        try {
            return Math.multiplyExact(l, r);
        } catch (ArithmeticException e) {
            throw new VALUE_ERROR("data overflow in multiplication of BIGINT values");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static BigDecimal opMult(BigDecimal l, BigDecimal r) {
        if (l == null || r == null) {
            return null;
        }

        int p1 = l.precision();
        int s1 = l.scale();
        int p2 = r.precision();
        int s2 = r.scale();

        int maxPrecision = p1 + p2 + 1;
        int scale = s1 + s2;

        BigDecimal ret =
                l.multiply(r, new MathContext(maxPrecision, RoundingMode.HALF_UP))
                        .setScale(scale, RoundingMode.HALF_UP);
        if (ret.precision() > 38) {
            throw new VALUE_ERROR("the operation results in a precision higher than 38");
        }

        return ret;
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Float opMult(Float l, Float r) {
        if (l == null || r == null) {
            return null;
        }
        try {
            return checkFloat(l * r);
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow in multiplication of two FLOAT values");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Double opMult(Double l, Double r) {
        if (l == null || r == null) {
            return null;
        }
        try {
            return checkDouble(l * r);
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow in multiplication of two DOUBLE values");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Object opMult(Object l, Object r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }

        return opMultWithRuntimeTypeConv(l, r);
    }

    // ====================================
    // /
    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Object opDiv(Short l, Short r) {
        if (l == null || r == null) {
            return null;
        }
        if (r.equals((short) 0)) {
            throw new ZERO_DIVIDE();
        }
        if (Server.getSystemParameterBool(SysParam.ORACLE_COMPAT_NUMBER_BEHAVIOR)) {
            return opDiv(BigDecimal.valueOf(l.longValue()), BigDecimal.valueOf(r.longValue()));
        } else {
            return (short) (l / r);
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Object opDiv(Integer l, Integer r) {
        if (l == null || r == null) {
            return null;
        }
        if (r.equals(0)) {
            throw new ZERO_DIVIDE();
        }
        if (Server.getSystemParameterBool(SysParam.ORACLE_COMPAT_NUMBER_BEHAVIOR)) {
            return opDiv(BigDecimal.valueOf(l.longValue()), BigDecimal.valueOf(r.longValue()));
        } else {
            return l / r;
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Object opDiv(Long l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        if (r.equals(0L)) {
            throw new ZERO_DIVIDE();
        }

        if (Server.getSystemParameterBool(SysParam.ORACLE_COMPAT_NUMBER_BEHAVIOR)) {
            return opDiv(BigDecimal.valueOf(l), BigDecimal.valueOf(r));
        } else {
            return l / r;
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static BigDecimal opDiv(BigDecimal l, BigDecimal r) {
        if (l == null || r == null) {
            return null;
        }
        if (r.equals(BigDecimal.ZERO)) {
            throw new ZERO_DIVIDE();
        }

        int p1 = l.precision();
        int s1 = l.scale();
        int p2 = r.precision();
        int s2 = r.scale();

        int scale;
        if (Server.getSystemParameterBool(SysParam.COMPAT_NUMERIC_DIVISION_SCALE)) {
            scale = Math.max(s1, s2);
        } else {
            scale = Math.max(9, Math.max(s1, s2));
        }
        int maxPrecision = (p1 - s1) + s2 + scale;

        BigDecimal ret =
                l.divide(r, new MathContext(maxPrecision, RoundingMode.HALF_UP))
                        .setScale(scale, RoundingMode.HALF_UP);
        if (ret.precision() > 38) {
            throw new VALUE_ERROR("data overflow in division of NUMERIC values");
        }

        return ret;
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Float opDiv(Float l, Float r) {
        if (l == null || r == null) {
            return null;
        }
        if (r.equals(0.0f)) {
            throw new ZERO_DIVIDE();
        }
        try {
            return checkFloat(l / r);
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow in division of two FLOAT values");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Double opDiv(Double l, Double r) {
        if (l == null || r == null) {
            return null;
        }
        if (r.equals(0.0)) {
            throw new ZERO_DIVIDE();
        }
        try {
            return checkDouble(l / r);
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow in division of two DOUBLE values");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Object opDiv(Object l, Object r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }

        return opDivWithRuntimeTypeConv(l, r);
    }

    // ====================================
    // DIV
    @Operator(coercionScheme = CoercionScheme.IntArithOp)
    public static Short opDivInt(Short l, Short r) {
        if (l == null || r == null) {
            return null;
        }
        if (r.equals((short) 0)) {
            throw new ZERO_DIVIDE();
        }
        return (short) (l / r);
    }

    @Operator(coercionScheme = CoercionScheme.IntArithOp)
    public static Integer opDivInt(Integer l, Integer r) {
        if (l == null || r == null) {
            return null;
        }
        if (r.equals(0)) {
            throw new ZERO_DIVIDE();
        }
        return l / r;
    }

    @Operator(coercionScheme = CoercionScheme.IntArithOp)
    public static Long opDivInt(Long l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        if (r.equals(0L)) {
            throw new ZERO_DIVIDE();
        }
        return l / r;
    }

    @Operator(coercionScheme = CoercionScheme.IntArithOp)
    public static Object opDivInt(Object l, Object r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }

        return opDivIntWithRuntimeTypeConv(l, r);
    }

    // ====================================
    // MOD
    @Operator(coercionScheme = CoercionScheme.IntArithOp)
    public static Short opMod(Short l, Short r) {
        if (l == null || r == null) {
            return null;
        }
        if (r.equals((short) 0)) {
            throw new ZERO_DIVIDE();
        }
        return (short) (l % r);
    }

    @Operator(coercionScheme = CoercionScheme.IntArithOp)
    public static Integer opMod(Integer l, Integer r) {
        if (l == null || r == null) {
            return null;
        }
        if (r.equals(0)) {
            throw new ZERO_DIVIDE();
        }
        return l % r;
    }

    @Operator(coercionScheme = CoercionScheme.IntArithOp)
    public static Long opMod(Long l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        if (r.equals(0L)) {
            throw new ZERO_DIVIDE();
        }
        return l % r;
    }

    @Operator(coercionScheme = CoercionScheme.IntArithOp)
    public static Object opMod(Object l, Object r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }

        return opModWithRuntimeTypeConv(l, r);
    }

    // ====================================
    // +
    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static String opAdd(String l, String r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (l == null) {
                l = EMPTY_STRING;
            }
            if (r == null) {
                r = EMPTY_STRING;
            }
        }

        if (l == null || r == null) {
            return null;
        }
        return (l + r);
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Short opAdd(Short l, Short r) {
        if (l == null || r == null) {
            return null;
        }
        return addShortExact(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Integer opAdd(Integer l, Integer r) {
        if (l == null || r == null) {
            return null;
        }
        try {
            return Math.addExact(l, r);
        } catch (ArithmeticException e) {
            throw new VALUE_ERROR("data overflow in addition of INTEGER values");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Long opAdd(Long l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        try {
            return Math.addExact(l, r);
        } catch (ArithmeticException e) {
            throw new VALUE_ERROR("data overflow in addition of BIGINT values");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static BigDecimal opAdd(BigDecimal l, BigDecimal r) {
        if (l == null || r == null) {
            return null;
        }

        int p1 = l.precision();
        int s1 = l.scale();
        int p2 = r.precision();
        int s2 = r.scale();

        int maxPrecision = Math.max(p1 - s1, p2 - s2) + Math.max(s1, s2) + 1;
        int scale = Math.max(s1, s2);

        BigDecimal ret =
                l.add(r, new MathContext(maxPrecision, RoundingMode.HALF_UP))
                        .setScale(scale, RoundingMode.HALF_UP);
        if (ret.precision() > 38) {
            throw new VALUE_ERROR("the operation results in a precision higher than 38");
        }

        return ret;
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Float opAdd(Float l, Float r) {
        if (l == null || r == null) {
            return null;
        }
        try {
            return checkFloat(l + r);
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow in addition of two FLOAT values");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Double opAdd(Double l, Double r) {
        if (l == null || r == null) {
            return null;
        }
        try {
            return checkDouble(l + r);
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow in addition of two DOUBLE values");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Time opAdd(Time l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        LocalTime llt = l.toLocalTime();
        return Time.valueOf(llt.plusSeconds(r.longValue()));
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Time opAdd(Long l, Time r) {
        return opAdd(r, l);
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Date opAdd(Date l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        if (l.equals(ZERO_DATE)) {
            throw new VALUE_ERROR("attempt to use the zero DATE");
        }

        LocalDate lld = l.toLocalDate();
        Date ret = Date.valueOf(lld.plusDays(r.longValue()));
        if (checkDate(ret)) {
            return ret;
        } else {
            throw new VALUE_ERROR("not in the valid range of DATE type");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Date opAdd(Long l, Date r) {
        return opAdd(r, l);
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Timestamp opAdd(Timestamp l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        if (l.equals(ZERO_DATETIME)) {
            throw new VALUE_ERROR("attempt to use the zero DATETIME");
        }

        LocalDateTime lldt = l.toLocalDateTime();
        Timestamp ret = Timestamp.valueOf(lldt.plus(r.longValue(), ChronoUnit.MILLIS));
        if (checkDatetime(ret)) {
            return ret;
        } else {
            throw new VALUE_ERROR("not in the valid range of DATETIME type");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static ZonedDateTime opAdd(ZonedDateTime l, Long r) {
        // cannot be called actually, but only to register this operator with a parameter type
        // TIMESTAMP
        throw new PROGRAM_ERROR(); // unreachable
    }

    public static Timestamp opAddTimestamp(Timestamp l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        if (isZeroTimestamp(l)) {
            throw new VALUE_ERROR("attempt to use the zero TIMESTAMP");
        }
        assert l.getNanos() == 0;

        LocalDateTime lldt = l.toLocalDateTime();
        Timestamp ret = Timestamp.valueOf(lldt.plus(r.longValue(), ChronoUnit.SECONDS));
        ret = checkTimestamp(ret);
        if (ret != null) {
            return ret;
        } else {
            throw new VALUE_ERROR("not in the valid range of TIMESTAMP type");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Timestamp opAdd(Long l, Timestamp r) {
        return opAdd(r, l);
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static ZonedDateTime opAdd(Long l, ZonedDateTime r) {
        // cannot be called actually, but only to register this operator with a parameter type
        // TIMESTAMP
        throw new PROGRAM_ERROR(); // unreachable
    }

    public static Timestamp opAddTimestamp(Long l, Timestamp r) {
        return opAddTimestamp(r, l);
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Object opAdd(Object l, Object r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }

        return opAddWithRuntimeTypeConv(l, r);
    }

    // ====================================
    // -
    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Short opSubtract(Short l, Short r) {
        if (l == null || r == null) {
            return null;
        }
        return subtractShortExact(l, r);
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Integer opSubtract(Integer l, Integer r) {
        if (l == null || r == null) {
            return null;
        }
        try {
            return Math.subtractExact(l, r);
        } catch (ArithmeticException e) {
            throw new VALUE_ERROR("data overflow in subtraction of INTEGER values");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Long opSubtract(Long l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        try {
            return Math.subtractExact(l, r);
        } catch (ArithmeticException e) {
            throw new VALUE_ERROR("data overflow in subtraction of BIGINT values");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static BigDecimal opSubtract(BigDecimal l, BigDecimal r) {
        if (l == null || r == null) {
            return null;
        }

        int p1 = l.precision();
        int s1 = l.scale();
        int p2 = r.precision();
        int s2 = r.scale();

        int maxPrecision =
                Math.max(p1 - s1, p2 - s2)
                        + Math.max(s1, s2)
                        + 1; // +1: consider subtracting a minus value
        int scale = Math.max(s1, s2);

        BigDecimal ret =
                l.subtract(r, new MathContext(maxPrecision, RoundingMode.HALF_UP))
                        .setScale(scale, RoundingMode.HALF_UP);
        if (ret.precision() > 38) {
            throw new VALUE_ERROR("the operation results in a precision higher than 38");
        }

        return ret;
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Float opSubtract(Float l, Float r) {
        if (l == null || r == null) {
            return null;
        }
        try {
            return checkFloat(l - r);
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow in subtraction of two FLOAT values");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Double opSubtract(Double l, Double r) {
        if (l == null || r == null) {
            return null;
        }
        try {
            return checkDouble(l - r);
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow in subtraction of two DOUBLE values");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Long opSubtract(Time l, Time r) {
        if (l == null || r == null) {
            return null;
        }
        LocalTime llt = l.toLocalTime();
        LocalTime rlt = r.toLocalTime();
        return rlt.until(llt, ChronoUnit.SECONDS);
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Long opSubtract(Date l, Date r) {
        if (l == null || r == null) {
            return null;
        }
        if (l.equals(ZERO_DATE) || r.equals(ZERO_DATE)) {
            throw new VALUE_ERROR("attempt to use the zero DATE");
        }

        LocalDate lld = l.toLocalDate();
        LocalDate rld = r.toLocalDate();
        return rld.until(lld, ChronoUnit.DAYS);
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Long opSubtract(Timestamp l, Timestamp r) {
        if (l == null || r == null) {
            return null;
        }
        if (l.equals(ZERO_DATETIME) || r.equals(ZERO_DATETIME)) {
            throw new VALUE_ERROR("attempt to use the zero DATETIME");
        }

        LocalDateTime lldt = l.toLocalDateTime();
        LocalDateTime rldt = r.toLocalDateTime();
        return rldt.until(lldt, ChronoUnit.MILLIS);
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Long opSubtract(ZonedDateTime l, ZonedDateTime r) {
        // cannot be called actually, but only to register this operator with a parameter type
        // TIMESTAMP
        throw new PROGRAM_ERROR(); // unreachable
    }

    public static Long opSubtractTimestamp(Timestamp l, Timestamp r) {
        if (l == null || r == null) {
            return null;
        }
        if (isZeroTimestamp(l) || isZeroTimestamp(r)) {
            throw new VALUE_ERROR("attempt to use the zero TIMESTAMP");
        }
        assert l.getNanos() == 0;
        assert r.getNanos() == 0;

        LocalDateTime lldt = l.toLocalDateTime();
        LocalDateTime rldt = r.toLocalDateTime();
        return rldt.until(lldt, ChronoUnit.SECONDS);
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Time opSubtract(Time l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        LocalTime llt = l.toLocalTime();
        return Time.valueOf(llt.minusSeconds(r.longValue()));
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Date opSubtract(Date l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        if (l.equals(ZERO_DATE)) {
            throw new VALUE_ERROR("attempt to use the zero DATE");
        }

        LocalDate lld = l.toLocalDate();
        Date ret = Date.valueOf(lld.minusDays(r.longValue()));
        if (checkDate(ret)) {
            return ret;
        } else {
            throw new VALUE_ERROR("not in the valid range of DATE type");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Timestamp opSubtract(Timestamp l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        if (l.equals(ZERO_DATETIME)) {
            throw new VALUE_ERROR("attempt to use the zero DATETIME");
        }

        LocalDateTime lldt = l.toLocalDateTime();
        Timestamp ret = Timestamp.valueOf(lldt.minus(r.longValue(), ChronoUnit.MILLIS));
        if (checkDatetime(ret)) {
            return ret;
        } else {
            throw new VALUE_ERROR("not in the valid range of DATETIME type");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static ZonedDateTime opSubtract(ZonedDateTime l, Long r) {
        // cannot be called actually, but only to register this operator with a parameter type
        // TIMESTAMP
        throw new PROGRAM_ERROR(); // unreachable
    }

    public static Timestamp opSubtractTimestamp(Timestamp l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        if (isZeroTimestamp(l)) {
            throw new VALUE_ERROR("attempt to use the zero TIMESTAMP");
        }
        assert l.getNanos() == 0;

        LocalDateTime lldt = l.toLocalDateTime();
        Timestamp ret = Timestamp.valueOf(lldt.minus(r.longValue(), ChronoUnit.SECONDS));
        ret = checkTimestamp(ret);
        if (ret != null) {
            return ret;
        } else {
            throw new VALUE_ERROR("not in the valid range of TIMESTAMP type");
        }
    }

    @Operator(coercionScheme = CoercionScheme.ArithOp)
    public static Object opSubtract(Object l, Object r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(l)) {
                l = null;
            }
            if (EMPTY_STRING.equals(r)) {
                r = null;
            }
        }

        if (l == null || r == null) {
            return null;
        }

        return opSubtractWithRuntimeTypeConv(l, r);
    }

    // ====================================
    // ||
    @Operator(coercionScheme = CoercionScheme.StringOp)
    public static String opConcat(String l, String r) {
        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (l == null) {
                l = EMPTY_STRING;
            }
            if (r == null) {
                r = EMPTY_STRING;
            }
        }

        if (l == null || r == null) {
            return null;
        }
        return l + r;
    }

    // ====================================
    // <<
    @Operator(coercionScheme = CoercionScheme.BitOp)
    public static Long opBitShiftLeft(Long l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        return l << r;
    }

    // ====================================
    // >>
    @Operator(coercionScheme = CoercionScheme.BitOp)
    public static Long opBitShiftRight(Long l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        return l >> r;
    }

    // ====================================
    // &
    @Operator(coercionScheme = CoercionScheme.BitOp)
    public static Long opBitAnd(Long l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        return l & r;
    }

    // ====================================
    // ^
    @Operator(coercionScheme = CoercionScheme.BitOp)
    public static Long opBitXor(Long l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        return l ^ r;
    }

    // ====================================
    // |
    @Operator(coercionScheme = CoercionScheme.BitOp)
    public static Long opBitOr(Long l, Long r) {
        if (l == null || r == null) {
            return null;
        }
        return l | r;
    }

    // ====================================
    // like
    @Operator(coercionScheme = CoercionScheme.StringOp)
    public static Boolean opLike(String s, String pattern, String escape) {
        assert pattern != null;
        assert escape == null || escape.length() == 1;

        if (s == null) {
            return null;
        }

        String regex = getRegexForLike(pattern, escape);
        try {
            return s.matches(regex);
        } catch (PatternSyntaxException e) {
            throw new PROGRAM_ERROR(); // unreachable
        }
    }

    // ------------------------------------
    // coercions
    // ------------------------------------

    // from datetime
    public static Date convDatetimeToDate(Timestamp e) {
        if (e == null) {
            return null;
        }
        if (e.equals(ZERO_DATETIME)) {
            return ZERO_DATE;
        }

        return new Date(e.getYear(), e.getMonth(), e.getDate());
    }

    public static Time convDatetimeToTime(Timestamp e) {
        if (e == null) {
            return null;
        }

        return new Time(e.getHours(), e.getMinutes(), e.getSeconds());
    }

    public static Timestamp convDatetimeToTimestamp(Timestamp e) {
        if (e == null) {
            return null;
        }
        if (e.equals(ZERO_DATETIME)) {
            return ZERO_TIMESTAMP;
        }

        Timestamp ret =
                new Timestamp(
                        e.getYear(),
                        e.getMonth(),
                        e.getDate(),
                        e.getHours(),
                        e.getMinutes(),
                        e.getSeconds(),
                        0);
        ret = checkTimestamp(ret);
        if (ret != null) {
            return ret;
        } else {
            throw new VALUE_ERROR("not in the valid range of TIMESTAMP type");
        }
    }

    public static String convDatetimeToString(Timestamp e) {
        if (e == null) {
            return null;
        }
        if (e.equals(ZERO_DATETIME)) {
            // must be calculated everytime because the AM/PM indicator can change according to the
            // locale change
            return String.format("12:00:00.000 %s 00/00/0000", AM_PM.format(ZERO_DATE));
        }

        return DATETIME_FORMAT.format(e);
    }

    // from date
    public static Timestamp convDateToDatetime(Date e) {
        if (e == null) {
            return null;
        }
        if (e.equals(ZERO_DATE)) {
            return ZERO_DATETIME;
        }

        return new Timestamp(e.getYear(), e.getMonth(), e.getDate(), 0, 0, 0, 0);
    }

    public static Timestamp convDateToTimestamp(Date e) {
        if (e == null) {
            return null;
        }
        if (e.equals(ZERO_DATE)) {
            return ZERO_TIMESTAMP;
        }

        Timestamp ret = new Timestamp(e.getYear(), e.getMonth(), e.getDate(), 0, 0, 0, 0);
        ret = checkTimestamp(ret);
        if (ret != null) {
            return ret;
        } else {
            throw new VALUE_ERROR("not in the valid range of TIMESTAMP type");
        }
    }

    public static String convDateToString(Date e) {
        if (e == null) {
            return null;
        }
        if (e.equals(ZERO_DATE)) {
            return "00/00/0000";
        }

        return DATE_FORMAT.format(e);
    }

    // from time
    public static String convTimeToString(Time e) {
        if (e == null) {
            return null;
        }

        return TIME_FORMAT.format(e);
    }

    // from timestamp
    public static Timestamp convTimestampToDatetime(Timestamp e) {
        if (e == null) {
            return null;
        }

        if (isZeroTimestamp(e)) {
            return ZERO_DATETIME;
        }
        assert e.getNanos() == 0;

        return new Timestamp(
                e.getYear(),
                e.getMonth(),
                e.getDate(),
                e.getHours(),
                e.getMinutes(),
                e.getSeconds(),
                0);
    }

    public static Date convTimestampToDate(Timestamp e) {
        if (e == null) {
            return null;
        }

        if (isZeroTimestamp(e)) {
            return ZERO_DATE;
        }
        assert e.getNanos() == 0;

        return new Date(e.getYear(), e.getMonth(), e.getDate());
    }

    public static Time convTimestampToTime(Timestamp e) {
        if (e == null) {
            return null;
        }
        assert e.getNanos() == 0;

        return new Time(e.getHours(), e.getMinutes(), e.getSeconds());
    }

    public static String convTimestampToString(Timestamp e) {
        if (e == null) {
            return null;
        }
        assert e.getNanos() == 0;

        if (isZeroTimestamp(e)) {
            // must be calculated everytime because the AM/PM indicator can change according to the
            // locale change
            return String.format("12:00:00 %s 00/00/0000", AM_PM.format(ZERO_DATE));
        }

        Instant instant = Instant.ofEpochMilli(e.getTime());
        ZoneId timezone = Server.getConfig().getTimeZone();
        ZonedDateTime zdt = ZonedDateTime.ofInstant(instant, timezone);
        return zdt.format(TIMESTAMP_FORMAT);
    }

    // from double
    public static Time convDoubleToTime(Double e) {
        if (e == null) {
            return null;
        }

        long l = doubleToLong(e.doubleValue());
        return longToTime(l);
    }

    public static Timestamp convDoubleToTimestamp(Double e) {
        if (e == null) {
            return null;
        }

        long l = doubleToLong(e.doubleValue());
        return longToTimestamp(l);
    }

    public static Integer convDoubleToInt(Double e) {
        if (e == null) {
            return null;
        }

        return Integer.valueOf(doubleToInt(e.doubleValue()));
    }

    public static Short convDoubleToShort(Double e) {
        if (e == null) {
            return null;
        }

        return Short.valueOf(doubleToShort(e.doubleValue()));
    }

    public static Byte convDoubleToByte(Double e) {
        if (e == null) {
            return null;
        }

        return Byte.valueOf(doubleToByte(e.doubleValue()));
    }

    public static String convDoubleToString(Double e) {
        if (e == null) {
            return null;
        }

        if (Server.getSystemParameterBool(SysParam.ORACLE_COMPAT_NUMBER_BEHAVIOR)) {
            BigDecimal bd = new BigDecimal(e.doubleValue(), doubleToStringContext);
            return detachTrailingZeros(bd.toPlainString());
        } else {
            return String.format("%.15e", e);
        }
    }

    public static Float convDoubleToFloat(Double e) {
        if (e == null) {
            return null;
        }

        try {
            return checkFloat(Float.valueOf(e.floatValue()));
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow on data type FLOAT: " + e);
        }
    }

    public static BigDecimal convDoubleToNumeric(Double e) {
        if (e == null) {
            return null;
        }

        return BigDecimal.valueOf(e.doubleValue()).round(doubleToNumericContext);
    }

    public static Long convDoubleToBigint(Double e) {
        if (e == null) {
            return null;
        }

        return Long.valueOf(doubleToLong(e.doubleValue()));
    }

    // from float
    public static Time convFloatToTime(Float e) {
        if (e == null) {
            return null;
        }

        long l = doubleToLong(e.doubleValue());
        return longToTime(l);
    }

    public static Timestamp convFloatToTimestamp(Float e) {
        if (e == null) {
            return null;
        }

        long l = doubleToLong(e.doubleValue());
        return longToTimestamp(l);
    }

    public static Integer convFloatToInt(Float e) {
        if (e == null) {
            return null;
        }

        return Integer.valueOf(doubleToInt(e.doubleValue()));
    }

    public static Short convFloatToShort(Float e) {
        if (e == null) {
            return null;
        }

        return Short.valueOf(doubleToShort(e.doubleValue()));
    }

    public static Byte convFloatToByte(Float e) {
        if (e == null) {
            return null;
        }

        return Byte.valueOf(doubleToByte(e.doubleValue()));
    }

    public static String convFloatToString(Float e) {
        if (e == null) {
            return null;
        }

        if (Server.getSystemParameterBool(SysParam.ORACLE_COMPAT_NUMBER_BEHAVIOR)) {
            BigDecimal bd = new BigDecimal(e.doubleValue(), floatToStringContext);
            return detachTrailingZeros(bd.toPlainString());
        } else {
            return String.format("%.6e", e);
        }
    }

    public static Double convFloatToDouble(Float e) {
        if (e == null) {
            return null;
        }

        try {
            return checkDouble(Double.valueOf(e.doubleValue()));
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow on data type DOUBLE: " + e);
        }
    }

    public static BigDecimal convFloatToNumeric(Float e) {
        if (e == null) {
            return null;
        }

        return BigDecimal.valueOf(e.doubleValue()).round(floatToNumericContext);
    }

    public static Long convFloatToBigint(Float e) {
        if (e == null) {
            return null;
        }

        return Long.valueOf(doubleToLong(e.doubleValue()));
    }

    // from numeric
    public static Timestamp convNumericToTimestamp(BigDecimal e) {
        if (e == null) {
            return null;
        }

        long l = bigDecimalToLong(e);
        return longToTimestamp(l);
    }

    public static Integer convNumericToInt(BigDecimal e) {
        if (e == null) {
            return null;
        }

        return Integer.valueOf(bigDecimalToInt(e));
    }

    public static Short convNumericToShort(BigDecimal e) {
        if (e == null) {
            return null;
        }

        return Short.valueOf(bigDecimalToShort(e));
    }

    public static Byte convNumericToByte(BigDecimal e) {
        if (e == null) {
            return null;
        }

        return Byte.valueOf(bigDecimalToByte(e));
    }

    public static String convNumericToString(BigDecimal e) {
        if (e == null) {
            return null;
        }

        if (Server.getSystemParameterBool(SysParam.ORACLE_COMPAT_NUMBER_BEHAVIOR)) {
            return detachTrailingZeros(e.toPlainString());
        } else {
            return e.toString();
        }
    }

    public static Double convNumericToDouble(BigDecimal e) {
        if (e == null) {
            return null;
        }

        try {
            return checkDouble(Double.valueOf(e.doubleValue()));
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow on data type DOUBLE: " + e);
        }
    }

    public static Float convNumericToFloat(BigDecimal e) {
        if (e == null) {
            return null;
        }

        try {
            return checkFloat(Float.valueOf(e.floatValue()));
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow on data type FLOAT: " + e);
        }
    }

    public static Long convNumericToBigint(BigDecimal e) {
        if (e == null) {
            return null;
        }

        return Long.valueOf(bigDecimalToLong(e));
    }

    // from bigint
    public static Time convBigintToTime(Long e) {
        if (e == null) {
            return null;
        }

        return longToTime(e.longValue());
    }

    public static Timestamp convBigintToTimestamp(Long e) {
        if (e == null) {
            return null;
        }

        return longToTimestamp(e.longValue());
    }

    public static Integer convBigintToInt(Long e) {
        if (e == null) {
            return null;
        }

        return Integer.valueOf(longToInt(e.longValue()));
    }

    public static Short convBigintToShort(Long e) {
        if (e == null) {
            return null;
        }

        return Short.valueOf(longToShort(e.longValue()));
    }

    public static Byte convBigintToByte(Long e) {
        if (e == null) {
            return null;
        }

        return Byte.valueOf(longToByte(e.longValue()));
    }

    public static String convBigintToString(Long e) {
        if (e == null) {
            return null;
        }

        return e.toString();
    }

    public static Double convBigintToDouble(Long e) {
        if (e == null) {
            return null;
        }

        try {
            return checkDouble(Double.valueOf(e.doubleValue()));
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow on data type DOUBLE: " + e);
        }
    }

    public static Float convBigintToFloat(Long e) {
        if (e == null) {
            return null;
        }

        try {
            return checkFloat(Float.valueOf(e.floatValue()));
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow on data type FLOAT: " + e);
        }
    }

    public static BigDecimal convBigintToNumeric(Long e) {
        if (e == null) {
            return null;
        }

        return BigDecimal.valueOf(e.longValue());
    }

    // from int
    public static Time convIntToTime(Integer e) {
        if (e == null) {
            return null;
        }

        return longToTime(e.longValue());
    }

    public static Timestamp convIntToTimestamp(Integer e) {
        if (e == null) {
            return null;
        }

        return longToTimestamp(e.longValue());
    }

    public static Short convIntToShort(Integer e) {
        if (e == null) {
            return null;
        }

        return Short.valueOf(longToShort(e.longValue()));
    }

    public static Byte convIntToByte(Integer e) {
        if (e == null) {
            return null;
        }

        return Byte.valueOf(longToByte(e.longValue()));
    }

    public static String convIntToString(Integer e) {
        if (e == null) {
            return null;
        }

        return e.toString();
    }

    public static Double convIntToDouble(Integer e) {
        if (e == null) {
            return null;
        }

        try {
            return checkDouble(Double.valueOf(e.doubleValue()));
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow on data type DOUBLE: " + e);
        }
    }

    public static Float convIntToFloat(Integer e) {
        if (e == null) {
            return null;
        }

        try {
            return checkFloat(Float.valueOf(e.floatValue()));
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow on data type FLOAT: " + e);
        }
    }

    public static BigDecimal convIntToNumeric(Integer e) {
        if (e == null) {
            return null;
        }

        return BigDecimal.valueOf(e.longValue());
    }

    public static Long convIntToBigint(Integer e) {
        if (e == null) {
            return null;
        }

        return Long.valueOf(e.longValue());
    }

    // from short
    public static Time convShortToTime(Short e) {
        if (e == null) {
            return null;
        }

        return longToTime(e.longValue());
    }

    public static Timestamp convShortToTimestamp(Short e) {
        if (e == null) {
            return null;
        }

        return longToTimestamp(e.longValue());
    }

    public static Integer convShortToInt(Short e) {
        if (e == null) {
            return null;
        }

        return Integer.valueOf(e.intValue());
    }

    public static Byte convShortToByte(Short e) {
        if (e == null) {
            return null;
        }

        return Byte.valueOf(longToByte(e.longValue()));
    }

    public static String convShortToString(Short e) {
        if (e == null) {
            return null;
        }

        return e.toString();
    }

    public static Double convShortToDouble(Short e) {
        if (e == null) {
            return null;
        }

        try {
            return checkDouble(Double.valueOf(e.doubleValue()));
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow on data type DOUBLE: " + e);
        }
    }

    public static Float convShortToFloat(Short e) {
        if (e == null) {
            return null;
        }

        try {
            return checkFloat(Float.valueOf(e.floatValue()));
        } catch (VALUE_ERROR ee) {
            throw new VALUE_ERROR("data overflow on data type FLOAT: " + e);
        }
    }

    public static BigDecimal convShortToNumeric(Short e) {
        if (e == null) {
            return null;
        }

        return BigDecimal.valueOf(e.longValue());
    }

    public static Long convShortToBigint(Short e) {
        if (e == null) {
            return null;
        }

        return Long.valueOf(e.longValue());
    }

    // from string
    public static Timestamp convStringToDatetime(String e) {
        if (e == null) {
            return null;
        }

        LocalDateTime dt = DateTimeParser.DatetimeLiteral.parse(e);
        if (dt == null) {
            // invalid string
            throw new VALUE_ERROR("invalid DATETIME string: '" + e + "'");
        }

        if (dt.equals(DateTimeParser.nullDatetime)) {
            return ZERO_DATETIME;
        } else {
            return new Timestamp(
                    dt.getYear() - 1900,
                    dt.getMonthValue() - 1,
                    dt.getDayOfMonth(),
                    dt.getHour(),
                    dt.getMinute(),
                    dt.getSecond(),
                    dt.getNano());
        }
    }

    public static Date convStringToDate(String e) {
        if (e == null) {
            return null;
        }

        LocalDate d = DateTimeParser.DateLiteral.parse(e);
        if (d == null) {
            // invalid string
            throw new VALUE_ERROR("invalid DATE string: '" + e + "'");
        }

        if (d.equals(DateTimeParser.nullDate)) {
            return ZERO_DATE;
        } else {
            return new Date(d.getYear() - 1900, d.getMonthValue() - 1, d.getDayOfMonth());
        }
    }

    public static Time convStringToTime(String e) {
        if (e == null) {
            return null;
        }

        LocalTime t = DateTimeParser.TimeLiteral.parse(e);
        if (t == null) {
            // invalid string
            throw new VALUE_ERROR("invalid TIME string: '" + e + "'");
        }

        return new Time(t.getHour(), t.getMinute(), t.getSecond());
    }

    public static Timestamp convStringToTimestamp(String e) {
        if (e == null) {
            return null;
        }

        ZonedDateTime zdt = DateTimeParser.TimestampLiteral.parse(e);
        if (zdt == null) {
            // invalid string
            throw new VALUE_ERROR("invalid TIMESTAMP string: '" + e + "'");
        }

        if (zdt.equals(DateTimeParser.nullDatetimeGMT)) {
            return ZERO_TIMESTAMP;
        } else {
            assert zdt.getNano() == 0;
            return new Timestamp(
                    zdt.getYear() - 1900,
                    zdt.getMonthValue() - 1,
                    zdt.getDayOfMonth(),
                    zdt.getHour(),
                    zdt.getMinute(),
                    zdt.getSecond(),
                    0);
        }
    }

    public static Integer convStringToInt(String e) {
        if (e == null) {
            return null;
        }

        e = e.trim();
        if (e.length() == 0) {
            return INT_ZERO;
        }

        BigDecimal bd = strToBigDecimal(e);
        ;
        return Integer.valueOf(bigDecimalToInt(bd));
    }

    public static Short convStringToShort(String e) {
        if (e == null) {
            return null;
        }

        e = e.trim();
        if (e.length() == 0) {
            return SHORT_ZERO;
        }

        BigDecimal bd = strToBigDecimal(e);
        ;
        return Short.valueOf(bigDecimalToShort(bd));
    }

    public static Byte convStringToByte(String e) {
        if (e == null) {
            return null;
        }

        e = e.trim();
        if (e.length() == 0) {
            return BYTE_ZERO;
        }

        BigDecimal bd = strToBigDecimal(e);
        ;
        return Byte.valueOf(bigDecimalToByte(bd));
    }

    public static Double convStringToDouble(String e) {
        if (e == null) {
            return null;
        }

        e = e.trim();
        if (e.length() == 0) {
            return DOUBLE_ZERO;
        }

        try {
            return checkDouble(Double.valueOf(e));
        } catch (NumberFormatException ex) {
            throw new VALUE_ERROR("invalid DOUBLE string: '" + e + "'");
        }
    }

    public static Float convStringToFloat(String e) {
        if (e == null) {
            return null;
        }

        e = e.trim();
        if (e.length() == 0) {
            return FLOAT_ZERO;
        }

        try {
            return checkFloat(Float.valueOf(e));
        } catch (NumberFormatException ex) {
            throw new VALUE_ERROR("invalid FLOAT string: '" + e + "'");
        }
    }

    public static BigDecimal convStringToNumeric(String e) {
        if (e == null || e.length() == 0) {
            return null;
        }

        return strToBigDecimal(e);
    }

    public static Long convStringToBigint(String e) {
        if (e == null) {
            return null;
        }

        e = e.trim();
        if (e.length() == 0) {
            return LONG_ZERO;
        }

        BigDecimal bd = strToBigDecimal(e);
        return Long.valueOf(bigDecimalToLong(bd));
    }

    // from Object
    public static Timestamp convObjectToDatetime(Object e) {
        if (e == null) {
            return null;
        }

        if (e instanceof String) {
            return convStringToDatetime((String) e);
        } else if (e instanceof Date) {
            return convDateToDatetime((Date) e);
        } else if (e instanceof Timestamp) {
            // e is DATETIME or TIMESTAMP
            return (Timestamp) e;
        }

        throw new VALUE_ERROR("not compatible with DATETIME");
    }

    public static Date convObjectToDate(Object e) {
        if (e == null) {
            return null;
        }

        if (e instanceof String) {
            return convStringToDate((String) e);
        } else if (e instanceof Date) {
            return (Date) e;
        } else if (e instanceof Timestamp) {
            // e is DATETIME or TIMESTAMP
            return convDatetimeToDate((Timestamp) e);
        }

        throw new VALUE_ERROR("not compatible with DATE");
    }

    public static Time convObjectToTime(Object e) {
        if (e == null) {
            return null;
        }

        if (e instanceof String) {
            return convStringToTime((String) e);
        } else if (e instanceof Short) {
            return convShortToTime((Short) e);
        } else if (e instanceof Integer) {
            return convIntToTime((Integer) e);
        } else if (e instanceof Long) {
            return convBigintToTime((Long) e);
        } else if (e instanceof Float) {
            return convFloatToTime((Float) e);
        } else if (e instanceof Double) {
            return convDoubleToTime((Double) e);
        } else if (e instanceof Time) {
            return (Time) e;
        } else if (e instanceof Timestamp) {
            // e is DATETIME or TIMESTAMP
            return convDatetimeToTime((Timestamp) e);
        }

        throw new VALUE_ERROR("not compatible with TIME");
    }

    public static Timestamp convObjectToTimestamp(Object e) {
        if (e == null) {
            return null;
        }

        if (e instanceof String) {
            return convStringToTimestamp((String) e);
        } else if (e instanceof Short) {
            return convShortToTimestamp((Short) e);
        } else if (e instanceof Integer) {
            return convIntToTimestamp((Integer) e);
        } else if (e instanceof Long) {
            return convBigintToTimestamp((Long) e);
        } else if (e instanceof BigDecimal) {
            return convNumericToTimestamp((BigDecimal) e);
        } else if (e instanceof Float) {
            return convFloatToTimestamp((Float) e);
        } else if (e instanceof Double) {
            return convDoubleToTimestamp((Double) e);
        } else if (e instanceof Date) {
            return convDateToTimestamp((Date) e);
        } else if (e instanceof Timestamp) {
            // e is DATETIME or TIMESTAMP
            return convDatetimeToTimestamp((Timestamp) e);
        }

        throw new VALUE_ERROR("not compatible with TIMESTAMP");
    }

    public static Integer convObjectToInt(Object e) {
        if (e == null) {
            return null;
        }

        if (e instanceof String) {
            return convStringToInt((String) e);
        } else if (e instanceof Short) {
            return convShortToInt((Short) e);
        } else if (e instanceof Integer) {
            return (Integer) e;
        } else if (e instanceof Long) {
            return convBigintToInt((Long) e);
        } else if (e instanceof BigDecimal) {
            return convNumericToInt((BigDecimal) e);
        } else if (e instanceof Float) {
            return convFloatToInt((Float) e);
        } else if (e instanceof Double) {
            return convDoubleToInt((Double) e);
        }

        throw new VALUE_ERROR("not compatible with INTEGER");
    }

    public static Short convObjectToShort(Object e) {
        if (e == null) {
            return null;
        }

        if (e instanceof String) {
            return convStringToShort((String) e);
        } else if (e instanceof Short) {
            return (Short) e;
        } else if (e instanceof Integer) {
            return convIntToShort((Integer) e);
        } else if (e instanceof Long) {
            return convBigintToShort((Long) e);
        } else if (e instanceof BigDecimal) {
            return convNumericToShort((BigDecimal) e);
        } else if (e instanceof Float) {
            return convFloatToShort((Float) e);
        } else if (e instanceof Double) {
            return convDoubleToShort((Double) e);
        }

        throw new VALUE_ERROR("not compatible with SHORT");
    }

    public static String convObjectToString(Object e) {
        if (e == null) {
            return null;
        }

        if (e instanceof String) {
            return (String) e;
        } else if (e instanceof Short) {
            return convShortToString((Short) e);
        } else if (e instanceof Integer) {
            return convIntToString((Integer) e);
        } else if (e instanceof Long) {
            return convBigintToString((Long) e);
        } else if (e instanceof BigDecimal) {
            return convNumericToString((BigDecimal) e);
        } else if (e instanceof Float) {
            return convFloatToString((Float) e);
        } else if (e instanceof Double) {
            return convDoubleToString((Double) e);
        } else if (e instanceof Date) {
            return convDateToString((Date) e);
        } else if (e instanceof Time) {
            return convTimeToString((Time) e);
        } else if (e instanceof Timestamp) {
            // e is DATETIME or TIMESTAMP. impossible to figure out for now
            // TODO: match different Java types to DATETIME and TIMESTAMP, respectively
            throw new PROGRAM_ERROR("ambiguous run-time type: TIMESTAMP or DATETIME");
        }

        throw new VALUE_ERROR("not compatible with STRING");
    }

    public static Double convObjectToDouble(Object e) {
        if (e == null) {
            return null;
        }

        if (e instanceof String) {
            return convStringToDouble((String) e);
        } else if (e instanceof Short) {
            return convShortToDouble((Short) e);
        } else if (e instanceof Integer) {
            return convIntToDouble((Integer) e);
        } else if (e instanceof Long) {
            return convBigintToDouble((Long) e);
        } else if (e instanceof BigDecimal) {
            return convNumericToDouble((BigDecimal) e);
        } else if (e instanceof Float) {
            return convFloatToDouble((Float) e);
        } else if (e instanceof Double) {
            return (Double) e;
        }

        throw new VALUE_ERROR("not compatible with DOUBLE");
    }

    public static Float convObjectToFloat(Object e) {
        if (e == null) {
            return null;
        }

        if (e instanceof String) {
            return convStringToFloat((String) e);
        } else if (e instanceof Short) {
            return convShortToFloat((Short) e);
        } else if (e instanceof Integer) {
            return convIntToFloat((Integer) e);
        } else if (e instanceof Long) {
            return convBigintToFloat((Long) e);
        } else if (e instanceof BigDecimal) {
            return convNumericToFloat((BigDecimal) e);
        } else if (e instanceof Float) {
            return (Float) e;
        } else if (e instanceof Double) {
            return convDoubleToFloat((Double) e);
        }

        throw new VALUE_ERROR("not compatible with FLOAT");
    }

    public static BigDecimal convObjectToNumeric(Object e) {
        if (e == null) {
            return null;
        }

        if (e instanceof String) {
            return convStringToNumeric((String) e);
        } else if (e instanceof Short) {
            return convShortToNumeric((Short) e);
        } else if (e instanceof Integer) {
            return convIntToNumeric((Integer) e);
        } else if (e instanceof Long) {
            return convBigintToNumeric((Long) e);
        } else if (e instanceof BigDecimal) {
            return (BigDecimal) e;
        } else if (e instanceof Float) {
            return convFloatToNumeric((Float) e);
        } else if (e instanceof Double) {
            return convDoubleToNumeric((Double) e);
        }

        throw new VALUE_ERROR("not compatible with NUMERIC");
    }

    public static Long convObjectToBigint(Object e) {
        if (e == null) {
            return null;
        }

        if (e instanceof String) {
            return convStringToBigint((String) e);
        } else if (e instanceof Short) {
            return convShortToBigint((Short) e);
        } else if (e instanceof Integer) {
            return convIntToBigint((Integer) e);
        } else if (e instanceof Long) {
            return (Long) e;
        } else if (e instanceof BigDecimal) {
            return convNumericToBigint((BigDecimal) e);
        } else if (e instanceof Float) {
            return convFloatToBigint((Float) e);
        } else if (e instanceof Double) {
            return convDoubleToBigint((Double) e);
        }

        throw new VALUE_ERROR("not compatible with BIGINT");
    }

    // ------------------------------------------------
    // Private
    // ------------------------------------------------

    private static MathContext floatToStringContext = new MathContext(7, RoundingMode.HALF_UP);
    private static MathContext doubleToStringContext = new MathContext(16, RoundingMode.HALF_UP);
    private static MathContext floatToNumericContext = new MathContext(7, RoundingMode.DOWN);
    private static MathContext doubleToNumericContext = new MathContext(16, RoundingMode.DOWN);

    private static final Timestamp ZERO_TIMESTAMP =
            new Timestamp(0 - 1900, 0 - 1, 0, 0, 0, 0, 0); // TODO: CBRD-25595
    private static final Timestamp ZERO_TIMESTAMP_2 = new Timestamp(0); // Epoch

    private static final Date MIN_DATE = new Date(1 - 1900, 1 - 1, 1);
    private static final Date MAX_DATE = new Date(9999 - 1900, 12 - 1, 31);
    private static final Timestamp MIN_TIMESTAMP =
            new Timestamp(DateTimeParser.minTimestamp.toEpochSecond() * 1000);
    private static final Timestamp MAX_TIMESTAMP =
            new Timestamp(DateTimeParser.maxTimestamp.toEpochSecond() * 1000);
    private static final Timestamp MIN_DATETIME =
            Timestamp.valueOf(DateTimeParser.minDatetimeLocal);
    private static final Timestamp MAX_DATETIME =
            Timestamp.valueOf(DateTimeParser.maxDatetimeLocal);

    private static final String EMPTY_STRING = "";
    private static final int[] UNKNOWN_LINE_COLUMN = new int[] {-1, -1};

    private static final int CODE_CASE_NOT_FOUND = 0;
    private static final int CODE_CURSOR_ALREADY_OPEN = 1;
    private static final int CODE_INVALID_CURSOR = 2;
    private static final int CODE_NO_DATA_FOUND = 3;
    private static final int CODE_PROGRAM_ERROR = 4;
    private static final int CODE_STORAGE_ERROR = 5;
    private static final int CODE_SQL_ERROR = 6;
    private static final int CODE_TOO_MANY_ROWS = 7;
    private static final int CODE_VALUE_ERROR = 8;
    private static final int CODE_ZERO_DIVIDE = 9;
    private static final int CODE_APP_ERROR = 1000;

    private static final String MSG_CASE_NOT_FOUND = "case not found";
    private static final String MSG_CURSOR_ALREADY_OPEN = "cursor already open";
    private static final String MSG_INVALID_CURSOR = "invalid cursor";
    private static final String MSG_NO_DATA_FOUND = "no data found";
    private static final String MSG_PROGRAM_ERROR = "internal server error";
    private static final String MSG_STORAGE_ERROR = "storage error";
    private static final String MSG_SQL_ERROR = "SQL error";
    private static final String MSG_TOO_MANY_ROWS = "too many rows";
    private static final String MSG_VALUE_ERROR = "value error";
    private static final String MSG_ZERO_DIVIDE = "division by zero";
    private static final String MSG_APP_ERROR = "user defined exception";

    private static final Byte BYTE_ZERO = Byte.valueOf((byte) 0);
    private static final Short SHORT_ZERO = Short.valueOf((short) 0);
    private static final Integer INT_ZERO = Integer.valueOf(0);
    private static final Long LONG_ZERO = Long.valueOf(0L);
    private static final Float FLOAT_ZERO = Float.valueOf(0.0f);
    private static final Double DOUBLE_ZERO = Double.valueOf(0.0);

    // TODO: update the locale with the value got from the server
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("MM/dd/yyyy", Locale.US);
    private static final DateFormat TIME_FORMAT = new SimpleDateFormat("hh:mm:ss a", Locale.US);
    private static final DateFormat DATETIME_FORMAT =
            new SimpleDateFormat("hh:mm:ss.SSS a MM/dd/yyyy", Locale.US);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("hh:mm:ss a MM/dd/yyyy").withLocale(Locale.US);

    private static final DateFormat AM_PM = new SimpleDateFormat("a", Locale.US);

    private static Short shortOfInt(int i) {
        if (i <= Short.MAX_VALUE && i >= Short.MIN_VALUE) {
            return (short) i;
        } else {
            return null;
        }
    }

    private static Short addShortExact(Short l, Short r) {
        int v = l.intValue() + r.intValue(); // never overflows
        Short ret = shortOfInt(v);
        if (ret == null) {
            throw new VALUE_ERROR("data overflow in addition of SHORT values");
        }
        return ret;
    }

    private static Short subtractShortExact(Short l, Short r) {
        int v = l.intValue() - r.intValue(); // never overflows
        Short ret = shortOfInt(v);
        if (ret == null) {
            throw new VALUE_ERROR("data overflow in subtraction of SHORT values");
        }
        return ret;
    }

    private static Short negateShortExact(Short l) {
        int v = -l.intValue(); // never overflows
        Short ret = shortOfInt(v);
        if (ret == null) {
            throw new VALUE_ERROR("data overflow in negation of a SHORT value");
        }
        return ret;
    }

    private static Short multiplyShortExact(Short l, Short r) {
        int v = l.intValue() * r.intValue(); // never overflows
        Short ret = shortOfInt(v);
        if (ret == null) {
            throw new VALUE_ERROR("data overflow in multiplication of SHORT values");
        }
        return ret;
    }

    private static String rtrim(String s) {
        assert s != null;

        char[] cArr = s.toCharArray();
        int i, len = cArr.length;
        for (i = len - 1; i >= 0; i--) {
            if (cArr[i] != ' ') {
                break;
            }
        }

        return new String(cArr, 0, i + 1);
    }

    private static Boolean commonOpEq(Object l, Object r) {
        if (l == null || r == null) {
            return null;
        }
        return l.equals(r);
    }

    private static Boolean commonOpNullSafeEq(Object l, Object r) {
        if (l == null) {
            return (r == null);
        } else if (r == null) {
            return false;
        }
        return l.equals(r);
    }

    private static Boolean commonOpNeq(Object l, Object r) {
        if (l == null || r == null) {
            return null;
        }
        return !l.equals(r);
    }

    private static Boolean commonOpLe(Comparable l, Comparable r) {
        if (l == null || r == null) {
            return null;
        }
        return l.compareTo(r) <= 0;
    }

    private static Boolean commonOpLt(Comparable l, Comparable r) {
        if (l == null || r == null) {
            return null;
        }
        return l.compareTo(r) < 0;
    }

    private static Boolean commonOpGe(Comparable l, Comparable r) {
        if (l == null || r == null) {
            return null;
        }
        return l.compareTo(r) >= 0;
    }

    private static Boolean commonOpGt(Comparable l, Comparable r) {
        if (l == null || r == null) {
            return null;
        }
        return l.compareTo(r) > 0;
    }

    private static Boolean commonOpIn(Object o, Object... arr) {
        assert arr != null;

        if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
            if (EMPTY_STRING.equals(o)) {
                o = null;
            }
        }

        if (o == null) {
            return null;
        }
        boolean nullFound = false;
        for (Object p : arr) {
            if (Server.getSystemParameterBool(SysParam.ORACLE_STYLE_EMPTY_STRING)) {
                if (EMPTY_STRING.equals(p)) {
                    p = null;
                }
            }

            if (p == null) {
                nullFound = true;
            } else {
                if (o.equals(p)) {
                    return true;
                }
            }
        }
        return nullFound ? null : false;
    }

    private static String getRegexForLike(String pattern, String escape) {

        StringBuffer sbuf = new StringBuffer();

        int len = pattern.length();
        if (escape == null) {
            for (int i = 0; i < len; i++) {
                char c = pattern.charAt(i);
                if (c == '%') {
                    sbuf.append(".*");
                } else if (c == '_') {
                    sbuf.append(".");
                } else {
                    sbuf.append(c);
                }
            }
        } else {
            char esc = escape.charAt(0);
            for (int i = 0; i < len; i++) {
                char c = pattern.charAt(i);
                if (esc == c) {
                    if (i + 1 == len) {
                        sbuf.append(c); // append the escape character at the end of the pattern as
                        // CUBRID does
                    } else {
                        i++;
                        sbuf.append(
                                pattern.charAt(
                                        i)); // append it whether it is one of '%', '_', or the
                        // escape char.
                    }
                } else {
                    if (c == '%') {
                        sbuf.append(".*");
                    } else if (c == '_') {
                        sbuf.append(".");
                    } else {
                        sbuf.append(c);
                    }
                }
            }
        }

        return sbuf.toString();
    }

    private static long doubleToLong(double d) {
        BigDecimal bd = BigDecimal.valueOf(d);
        return bigDecimalToLong(bd);
    }

    private static int doubleToInt(double d) {
        BigDecimal bd = BigDecimal.valueOf(d);
        return bigDecimalToInt(bd);
    }

    private static short doubleToShort(double d) {
        BigDecimal bd = BigDecimal.valueOf(d);
        return bigDecimalToShort(bd);
    }

    private static byte doubleToByte(double d) {
        BigDecimal bd = BigDecimal.valueOf(d);
        return bigDecimalToByte(bd);
    }

    private static Time longToTime(long l) {
        if (l < 0L) {
            // negative values seem to result in a invalid time value
            // e.g.
            // select cast(cast(-1 as bigint) as time);
            // === <Result of SELECT Command in Line 1> ===
            //
            // <00001>  cast( cast(-1 as bigint) as time): 12:00:0/ AM
            //
            // 1 row selected. (0.004910 sec) Committed. (0.000020 sec)
            throw new VALUE_ERROR("negative values not allowed");
        }

        int totalSec = (int) (l % 86400L);
        int hour = totalSec / 3600;
        int minuteSec = totalSec % 3600;
        int min = minuteSec / 60;
        int sec = minuteSec % 60;
        return new Time(hour, min, sec);
    }

    private static Timestamp longToTimestamp(long l) {
        if (l < 0L) {
            //   select cast(cast(-100 as bigint) as timestamp);
            //   ERROR: Cannot coerce value of domain "bigint" to domain "timestamp"
            throw new VALUE_ERROR("negative values not allowed");
        } else if (l > 2147483647L) {
            // 2147483647L : see section 'implicit type conversion' in the user manual
            throw new VALUE_ERROR("values over 2,147,483,647 not allowed");
        } else {
            return new Timestamp(l * 1000L); // * 1000 : converts it to milli-seconds
        }
    }

    private static long bigDecimalToLong(BigDecimal bd) {
        // 1.5 -->2, and -1.5 --> -2 NOTE; different from // Math.round
        BigDecimal bdp = bd.setScale(0, RoundingMode.HALF_UP);
        try {
            return bdp.longValueExact();
        } catch (ArithmeticException e) {
            throw new VALUE_ERROR("data overflow on data type BIGINT: " + bd);
        }
    }

    private static int bigDecimalToInt(BigDecimal bd) {
        // 1.5 -->2, and -1.5 --> -2 NOTE; different from // Math.round
        BigDecimal bdp = bd.setScale(0, RoundingMode.HALF_UP);
        try {
            return bdp.intValueExact();
        } catch (ArithmeticException e) {
            throw new VALUE_ERROR("data overflow on data type INTEGER: " + bd);
        }
    }

    private static short bigDecimalToShort(BigDecimal bd) {
        // 1.5 -->2, and -1.5 --> -2 NOTE; different from // Math.round
        BigDecimal bdp = bd.setScale(0, RoundingMode.HALF_UP);
        try {
            return bdp.shortValueExact();
        } catch (ArithmeticException e) {
            throw new VALUE_ERROR("data overflow on data type SHORT: " + bd);
        }
    }

    private static byte bigDecimalToByte(BigDecimal bd) {
        // 1.5 -->2, and -1.5 --> -2 NOTE; different from // Math.round
        BigDecimal bdp = bd.setScale(0, RoundingMode.HALF_UP);
        try {
            return bdp.byteValueExact();
        } catch (ArithmeticException e) {
            throw new VALUE_ERROR("data overflow on data type BYTE: " + bd);
        }
    }

    private static int longToInt(long l) {
        return bigDecimalToInt(BigDecimal.valueOf(l));
    }

    private static short longToShort(long l) {
        return bigDecimalToShort(BigDecimal.valueOf(l));
    }

    private static byte longToByte(long l) {
        return bigDecimalToByte(BigDecimal.valueOf(l));
    }

    private static BigDecimal strToBigDecimal(String s) {
        try {
            BigDecimal ret = new BigDecimal(s);
            if (ret.precision() > 38) {
                throw new VALUE_ERROR("data overflow when converted to NUMERIC: '" + s + "'");
            }
            return ret;
        } catch (NumberFormatException e) {
            throw new VALUE_ERROR("invalid number string: '" + s + "'");
        }
    }

    private static Object opAddWithRuntimeTypeConv(Object l, Object r) {
        assert l != null;
        assert r != null;

        if (l instanceof Boolean) {
            // not applicable
        } else if (l instanceof String) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // string
                return opAdd((String) l, (String) r);
            } else if (r instanceof Short) {
                // double
                return opAdd(convStringToDouble((String) l), convShortToDouble((Short) r));
            } else if (r instanceof Integer) {
                // double
                return opAdd(convStringToDouble((String) l), convIntToDouble((Integer) r));
            } else if (r instanceof Long) {
                // double
                return opAdd(convStringToDouble((String) l), convBigintToDouble((Long) r));
            } else if (r instanceof BigDecimal) {
                // double
                return opAdd(convStringToDouble((String) l), convNumericToDouble((BigDecimal) r));
            } else if (r instanceof Float) {
                // double
                return opAdd(convStringToDouble((String) l), convFloatToDouble((Float) r));
            } else if (r instanceof Double) {
                // double
                return opAdd(convStringToDouble((String) l), (Double) r);
            } else if (r instanceof Date) {
                // (bigint, date)
                return opAdd(convStringToBigint((String) l), (Date) r);
            } else if (r instanceof Time) {
                // (bigint, time)
                return opAdd(convStringToBigint((String) l), (Time) r);
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Short) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opAdd(convShortToDouble((Short) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // short
                return opAdd((Short) l, (Short) r);
            } else if (r instanceof Integer) {
                // int
                return opAdd(convShortToInt((Short) l), (Integer) r);
            } else if (r instanceof Long) {
                // bigint
                return opAdd(convShortToBigint((Short) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // numeric
                return opAdd(convShortToNumeric((Short) l), (BigDecimal) r);
            } else if (r instanceof Float) {
                // float
                return opAdd(convShortToFloat((Short) l), (Float) r);
            } else if (r instanceof Double) {
                // double
                return opAdd(convShortToDouble((Short) l), (Double) r);
            } else if (r instanceof Date) {
                // (bigint, date)
                return opAdd(convShortToBigint((Short) l), (Date) r);
            } else if (r instanceof Time) {
                // (bigint, time)
                return opAdd(convShortToBigint((Short) l), (Time) r);
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Integer) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opAdd(convIntToDouble((Integer) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // int
                return opAdd((Integer) l, convShortToInt((Short) r));
            } else if (r instanceof Integer) {
                // int
                return opAdd((Integer) l, (Integer) r);
            } else if (r instanceof Long) {
                // bigint
                return opAdd(convIntToBigint((Integer) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // numeric
                return opAdd(convIntToNumeric((Integer) l), (BigDecimal) r);
            } else if (r instanceof Float) {
                // float
                return opAdd(convIntToFloat((Integer) l), (Float) r);
            } else if (r instanceof Double) {
                // double
                return opAdd(convIntToDouble((Integer) l), (Double) r);
            } else if (r instanceof Date) {
                // (bigint, date)
                return opAdd(convIntToBigint((Integer) l), (Date) r);
            } else if (r instanceof Time) {
                // (bigint, time)
                return opAdd(convIntToBigint((Integer) l), (Time) r);
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Long) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opAdd(convBigintToDouble((Long) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // bigint
                return opAdd((Long) l, convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // bigint
                return opAdd((Long) l, convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // bigint
                return opAdd((Long) l, (Long) r);
            } else if (r instanceof BigDecimal) {
                // numeric
                return opAdd(convBigintToNumeric((Long) l), (BigDecimal) r);
            } else if (r instanceof Float) {
                // float
                return opAdd(convBigintToFloat((Long) l), (Float) r);
            } else if (r instanceof Double) {
                // double
                return opAdd(convBigintToDouble((Long) l), (Double) r);
            } else if (r instanceof Date) {
                // (bigint, date)
                return opAdd((Long) l, (Date) r);
            } else if (r instanceof Time) {
                // (bigint, time)
                return opAdd((Long) l, (Time) r);
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof BigDecimal) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opAdd(convNumericToDouble((BigDecimal) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // numeric
                return opAdd((BigDecimal) l, convShortToNumeric((Short) r));
            } else if (r instanceof Integer) {
                // numeric
                return opAdd((BigDecimal) l, convIntToNumeric((Integer) r));
            } else if (r instanceof Long) {
                // numeric
                return opAdd((BigDecimal) l, convBigintToNumeric((Long) r));
            } else if (r instanceof BigDecimal) {
                // numeric
                return opAdd((BigDecimal) l, (BigDecimal) r);
            } else if (r instanceof Float) {
                // double
                return opAdd(convNumericToDouble((BigDecimal) l), convFloatToDouble((Float) r));
            } else if (r instanceof Double) {
                // double
                return opAdd(convNumericToDouble((BigDecimal) l), (Double) r);
            } else if (r instanceof Date) {
                // (bigint, date)
                return opAdd(convNumericToBigint((BigDecimal) l), (Date) r);
            } else if (r instanceof Time) {
                // (bigint, time)
                return opAdd(convNumericToBigint((BigDecimal) l), (Time) r);
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Float) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opAdd(convFloatToDouble((Float) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // float
                return opAdd((Float) l, convShortToFloat((Short) r));
            } else if (r instanceof Integer) {
                // float
                return opAdd((Float) l, convIntToFloat((Integer) r));
            } else if (r instanceof Long) {
                // float
                return opAdd((Float) l, convBigintToFloat((Long) r));
            } else if (r instanceof BigDecimal) {
                // double
                return opAdd(convFloatToDouble((Float) l), convNumericToDouble((BigDecimal) r));
            } else if (r instanceof Float) {
                // float
                return opAdd((Float) l, (Float) r);
            } else if (r instanceof Double) {
                // double
                return opAdd(convFloatToDouble((Float) l), (Double) r);
            } else if (r instanceof Date) {
                // (bigint, date)
                return opAdd(convFloatToBigint((Float) l), (Date) r);
            } else if (r instanceof Time) {
                // (bigint, time)
                return opAdd(convFloatToBigint((Float) l), (Time) r);
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Double) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opAdd((Double) l, convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // double
                return opAdd((Double) l, convShortToDouble((Short) r));
            } else if (r instanceof Integer) {
                // double
                return opAdd((Double) l, convIntToDouble((Integer) r));
            } else if (r instanceof Long) {
                // double
                return opAdd((Double) l, convBigintToDouble((Long) r));
            } else if (r instanceof BigDecimal) {
                // double
                return opAdd((Double) l, convNumericToDouble((BigDecimal) r));
            } else if (r instanceof Float) {
                // double
                return opAdd((Double) l, convFloatToDouble((Float) r));
            } else if (r instanceof Double) {
                // double
                return opAdd((Double) l, (Double) r);
            } else if (r instanceof Date) {
                // (bigint, date)
                return opAdd(convDoubleToBigint((Double) l), (Date) r);
            } else if (r instanceof Time) {
                // (bigint, time)
                return opAdd(convDoubleToBigint((Double) l), (Time) r);
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Date) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // (date, bigint)
                return opAdd((Date) l, convStringToBigint((String) r));
            } else if (r instanceof Short) {
                // (date, bigint)
                return opAdd((Date) l, convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // (date, bigint)
                return opAdd((Date) l, convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // (date, bigint)
                return opAdd((Date) l, (Long) r);
            } else if (r instanceof BigDecimal) {
                // (date, bigint)
                return opAdd((Date) l, convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // (date, bigint)
                return opAdd((Date) l, convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // (date, bigint)
                return opAdd((Date) l, convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Time) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // (time, bigint)
                return opAdd((Time) l, convStringToBigint((String) r));
            } else if (r instanceof Short) {
                // (time, bigint)
                return opAdd((Time) l, convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // (time, bigint)
                return opAdd((Time) l, convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // (time, bigint)
                return opAdd((Time) l, (Long) r);
            } else if (r instanceof BigDecimal) {
                // (time, bigint)
                return opAdd((Time) l, convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // (time, bigint)
                return opAdd((Time) l, convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // (time, bigint)
                return opAdd((Time) l, convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Timestamp) {
            throw new PROGRAM_ERROR("left operand's type is ambiguous: TIMESTAMP or DATETIME");
        }

        throw new VALUE_ERROR(
                String.format(
                        "cannot add two arguments due to their incompatible run-time types (%s, %s)",
                        plcsqlTypeOfJavaObject(l), plcsqlTypeOfJavaObject(r)));
    }

    private static Object opSubtractWithRuntimeTypeConv(Object l, Object r) {
        assert l != null;
        assert r != null;

        if (l instanceof Boolean) {
            // not applicable
        } else if (l instanceof String) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opSubtract(convStringToDouble((String) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // double
                return opSubtract(convStringToDouble((String) l), convShortToDouble((Short) r));
            } else if (r instanceof Integer) {
                // double
                return opSubtract(convStringToDouble((String) l), convIntToDouble((Integer) r));
            } else if (r instanceof Long) {
                // double
                return opSubtract(convStringToDouble((String) l), convBigintToDouble((Long) r));
            } else if (r instanceof BigDecimal) {
                // double
                return opSubtract(
                        convStringToDouble((String) l), convNumericToDouble((BigDecimal) r));
            } else if (r instanceof Float) {
                // double
                return opSubtract(convStringToDouble((String) l), convFloatToDouble((Float) r));
            } else if (r instanceof Double) {
                // double
                return opSubtract(convStringToDouble((String) l), (Double) r);
            } else if (r instanceof Date) {
                // datetime
                return opSubtract(convStringToDatetime((String) l), convDateToDatetime((Date) r));
            } else if (r instanceof Time) {
                // time
                return opSubtract(convStringToTime((String) l), (Time) r);
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Short) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opSubtract(convShortToDouble((Short) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // short
                return opSubtract((Short) l, (Short) r);
            } else if (r instanceof Integer) {
                // int
                return opSubtract(convShortToInt((Short) l), (Integer) r);
            } else if (r instanceof Long) {
                // bigint
                return opSubtract(convShortToBigint((Short) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // numeric
                return opSubtract(convShortToNumeric((Short) l), (BigDecimal) r);
            } else if (r instanceof Float) {
                // float
                return opSubtract(convShortToFloat((Short) l), (Float) r);
            } else if (r instanceof Double) {
                // double
                return opSubtract(convShortToDouble((Short) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Integer) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opSubtract(convIntToDouble((Integer) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // int
                return opSubtract((Integer) l, convShortToInt((Short) r));
            } else if (r instanceof Integer) {
                // int
                return opSubtract((Integer) l, (Integer) r);
            } else if (r instanceof Long) {
                // bigint
                return opSubtract(convIntToBigint((Integer) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // numeric
                return opSubtract(convIntToNumeric((Integer) l), (BigDecimal) r);
            } else if (r instanceof Float) {
                // float
                return opSubtract(convIntToFloat((Integer) l), (Float) r);
            } else if (r instanceof Double) {
                // double
                return opSubtract(convIntToDouble((Integer) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Long) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opSubtract(convBigintToDouble((Long) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // bigint
                return opSubtract((Long) l, convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // bigint
                return opSubtract((Long) l, convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // bigint
                return opSubtract((Long) l, (Long) r);
            } else if (r instanceof BigDecimal) {
                // numeric
                return opSubtract(convBigintToNumeric((Long) l), (BigDecimal) r);
            } else if (r instanceof Float) {
                // float
                return opSubtract(convBigintToFloat((Long) l), (Float) r);
            } else if (r instanceof Double) {
                // double
                return opSubtract(convBigintToDouble((Long) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof BigDecimal) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opSubtract(
                        convNumericToDouble((BigDecimal) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // numeric
                return opSubtract((BigDecimal) l, convShortToNumeric((Short) r));
            } else if (r instanceof Integer) {
                // numeric
                return opSubtract((BigDecimal) l, convIntToNumeric((Integer) r));
            } else if (r instanceof Long) {
                // numeric
                return opSubtract((BigDecimal) l, convBigintToNumeric((Long) r));
            } else if (r instanceof BigDecimal) {
                // numeric
                return opSubtract((BigDecimal) l, (BigDecimal) r);
            } else if (r instanceof Float) {
                // double
                return opSubtract(
                        convNumericToDouble((BigDecimal) l), convFloatToDouble((Float) r));
            } else if (r instanceof Double) {
                // double
                return opSubtract(convNumericToDouble((BigDecimal) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Float) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opSubtract(convFloatToDouble((Float) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // float
                return opSubtract((Float) l, convShortToFloat((Short) r));
            } else if (r instanceof Integer) {
                // float
                return opSubtract((Float) l, convIntToFloat((Integer) r));
            } else if (r instanceof Long) {
                // float
                return opSubtract((Float) l, convBigintToFloat((Long) r));
            } else if (r instanceof BigDecimal) {
                // double
                return opSubtract(
                        convFloatToDouble((Float) l), convNumericToDouble((BigDecimal) r));
            } else if (r instanceof Float) {
                // float
                return opSubtract((Float) l, (Float) r);
            } else if (r instanceof Double) {
                // double
                return opSubtract(convFloatToDouble((Float) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Double) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opSubtract((Double) l, convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // double
                return opSubtract((Double) l, convShortToDouble((Short) r));
            } else if (r instanceof Integer) {
                // double
                return opSubtract((Double) l, convIntToDouble((Integer) r));
            } else if (r instanceof Long) {
                // double
                return opSubtract((Double) l, convBigintToDouble((Long) r));
            } else if (r instanceof BigDecimal) {
                // double
                return opSubtract((Double) l, convNumericToDouble((BigDecimal) r));
            } else if (r instanceof Float) {
                // double
                return opSubtract((Double) l, convFloatToDouble((Float) r));
            } else if (r instanceof Double) {
                // double
                return opSubtract((Double) l, (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Date) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // datetime
                return opSubtract(convDateToDatetime((Date) l), convStringToDatetime((String) r));
            } else if (r instanceof Short) {
                // (date, bigint)
                return opSubtract((Date) l, convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // (date, bigint)
                return opSubtract((Date) l, convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // (date, bigint)
                return opSubtract((Date) l, (Long) r);
            } else if (r instanceof BigDecimal) {
                // (date, bigint)
                return opSubtract((Date) l, convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // (date, bigint)
                return opSubtract((Date) l, convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // (date, bigint)
                return opSubtract((Date) l, convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                return opSubtract((Date) l, (Date) r);
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Time) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // time
                return opSubtract((Time) l, convStringToTime((String) r));
            } else if (r instanceof Short) {
                // (time, bigint)
                return opSubtract((Time) l, convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // (time, bigint)
                return opSubtract((Time) l, convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // (time, bigint)
                return opSubtract((Time) l, (Long) r);
            } else if (r instanceof BigDecimal) {
                // (time, bigint)
                return opSubtract((Time) l, convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // (time, bigint)
                return opSubtract((Time) l, convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // (time, bigint)
                return opSubtract((Time) l, convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // time
                return opSubtract((Time) l, (Time) r);
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Timestamp) {
            throw new PROGRAM_ERROR("left operand's type is ambiguous: TIMESTAMP or DATETIME");
        }

        throw new VALUE_ERROR(
                String.format(
                        "cannot subtract two arguments due to their incompatible run-time types (%s, %s)",
                        plcsqlTypeOfJavaObject(l), plcsqlTypeOfJavaObject(r)));
    }

    private static Object opMultWithRuntimeTypeConv(Object l, Object r) {
        assert l != null;
        assert r != null;

        if (l instanceof Boolean) {
            // not applicable
        } else if (l instanceof String) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opMult(convStringToDouble((String) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // double
                return opMult(convStringToDouble((String) l), convShortToDouble((Short) r));
            } else if (r instanceof Integer) {
                // double
                return opMult(convStringToDouble((String) l), convIntToDouble((Integer) r));
            } else if (r instanceof Long) {
                // double
                return opMult(convStringToDouble((String) l), convBigintToDouble((Long) r));
            } else if (r instanceof BigDecimal) {
                // double
                return opMult(convStringToDouble((String) l), convNumericToDouble((BigDecimal) r));
            } else if (r instanceof Float) {
                // double
                return opMult(convStringToDouble((String) l), convFloatToDouble((Float) r));
            } else if (r instanceof Double) {
                // double
                return opMult(convStringToDouble((String) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Short) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opMult(convShortToDouble((Short) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // short
                return opMult((Short) l, (Short) r);
            } else if (r instanceof Integer) {
                // int
                return opMult(convShortToInt((Short) l), (Integer) r);
            } else if (r instanceof Long) {
                // bigint
                return opMult(convShortToBigint((Short) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // numeric
                return opMult(convShortToNumeric((Short) l), (BigDecimal) r);
            } else if (r instanceof Float) {
                // float
                return opMult(convShortToFloat((Short) l), (Float) r);
            } else if (r instanceof Double) {
                // double
                return opMult(convShortToDouble((Short) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Integer) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opMult(convIntToDouble((Integer) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // int
                return opMult((Integer) l, convShortToInt((Short) r));
            } else if (r instanceof Integer) {
                // int
                return opMult((Integer) l, (Integer) r);
            } else if (r instanceof Long) {
                // bigint
                return opMult(convIntToBigint((Integer) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // numeric
                return opMult(convIntToNumeric((Integer) l), (BigDecimal) r);
            } else if (r instanceof Float) {
                // float
                return opMult(convIntToFloat((Integer) l), (Float) r);
            } else if (r instanceof Double) {
                // double
                return opMult(convIntToDouble((Integer) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Long) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opMult(convBigintToDouble((Long) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // bigint
                return opMult((Long) l, convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // bigint
                return opMult((Long) l, convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // bigint
                return opMult((Long) l, (Long) r);
            } else if (r instanceof BigDecimal) {
                // numeric
                return opMult(convBigintToNumeric((Long) l), (BigDecimal) r);
            } else if (r instanceof Float) {
                // float
                return opMult(convBigintToFloat((Long) l), (Float) r);
            } else if (r instanceof Double) {
                // double
                return opMult(convBigintToDouble((Long) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof BigDecimal) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opMult(convNumericToDouble((BigDecimal) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // numeric
                return opMult((BigDecimal) l, convShortToNumeric((Short) r));
            } else if (r instanceof Integer) {
                // numeric
                return opMult((BigDecimal) l, convIntToNumeric((Integer) r));
            } else if (r instanceof Long) {
                // numeric
                return opMult((BigDecimal) l, convBigintToNumeric((Long) r));
            } else if (r instanceof BigDecimal) {
                // numeric
                return opMult((BigDecimal) l, (BigDecimal) r);
            } else if (r instanceof Float) {
                // double
                return opMult(convNumericToDouble((BigDecimal) l), convFloatToDouble((Float) r));
            } else if (r instanceof Double) {
                // double
                return opMult(convNumericToDouble((BigDecimal) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Float) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opMult(convFloatToDouble((Float) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // float
                return opMult((Float) l, convShortToFloat((Short) r));
            } else if (r instanceof Integer) {
                // float
                return opMult((Float) l, convIntToFloat((Integer) r));
            } else if (r instanceof Long) {
                // float
                return opMult((Float) l, convBigintToFloat((Long) r));
            } else if (r instanceof BigDecimal) {
                // double
                return opMult(convFloatToDouble((Float) l), convNumericToDouble((BigDecimal) r));
            } else if (r instanceof Float) {
                // float
                return opMult((Float) l, (Float) r);
            } else if (r instanceof Double) {
                // double
                return opMult(convFloatToDouble((Float) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Double) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opMult((Double) l, convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // double
                return opMult((Double) l, convShortToDouble((Short) r));
            } else if (r instanceof Integer) {
                // double
                return opMult((Double) l, convIntToDouble((Integer) r));
            } else if (r instanceof Long) {
                // double
                return opMult((Double) l, convBigintToDouble((Long) r));
            } else if (r instanceof BigDecimal) {
                // double
                return opMult((Double) l, convNumericToDouble((BigDecimal) r));
            } else if (r instanceof Float) {
                // double
                return opMult((Double) l, convFloatToDouble((Float) r));
            } else if (r instanceof Double) {
                // double
                return opMult((Double) l, (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Date) {
            // not applicable

        } else if (l instanceof Time) {
            // not applicable

        } else if (l instanceof Timestamp) {
            throw new PROGRAM_ERROR("left operand's type is ambiguous: TIMESTAMP or DATETIME");
        }

        throw new VALUE_ERROR(
                String.format(
                        "cannot multiply two arguments due to their incompatible run-time types (%s, %s)",
                        plcsqlTypeOfJavaObject(l), plcsqlTypeOfJavaObject(r)));
    }

    private static Object opDivWithRuntimeTypeConv(Object l, Object r) {
        assert l != null;
        assert r != null;

        if (l instanceof Boolean) {
            // not applicable
        } else if (l instanceof String) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opDiv(convStringToDouble((String) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // double
                return opDiv(convStringToDouble((String) l), convShortToDouble((Short) r));
            } else if (r instanceof Integer) {
                // double
                return opDiv(convStringToDouble((String) l), convIntToDouble((Integer) r));
            } else if (r instanceof Long) {
                // double
                return opDiv(convStringToDouble((String) l), convBigintToDouble((Long) r));
            } else if (r instanceof BigDecimal) {
                // double
                return opDiv(convStringToDouble((String) l), convNumericToDouble((BigDecimal) r));
            } else if (r instanceof Float) {
                // double
                return opDiv(convStringToDouble((String) l), convFloatToDouble((Float) r));
            } else if (r instanceof Double) {
                // double
                return opDiv(convStringToDouble((String) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Short) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opDiv(convShortToDouble((Short) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // short
                return opDiv((Short) l, (Short) r);
            } else if (r instanceof Integer) {
                // int
                return opDiv(convShortToInt((Short) l), (Integer) r);
            } else if (r instanceof Long) {
                // bigint
                return opDiv(convShortToBigint((Short) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // numeric
                return opDiv(convShortToNumeric((Short) l), (BigDecimal) r);
            } else if (r instanceof Float) {
                // float
                return opDiv(convShortToFloat((Short) l), (Float) r);
            } else if (r instanceof Double) {
                // double
                return opDiv(convShortToDouble((Short) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Integer) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opDiv(convIntToDouble((Integer) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // int
                return opDiv((Integer) l, convShortToInt((Short) r));
            } else if (r instanceof Integer) {
                // int
                return opDiv((Integer) l, (Integer) r);
            } else if (r instanceof Long) {
                // bigint
                return opDiv(convIntToBigint((Integer) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // numeric
                return opDiv(convIntToNumeric((Integer) l), (BigDecimal) r);
            } else if (r instanceof Float) {
                // float
                return opDiv(convIntToFloat((Integer) l), (Float) r);
            } else if (r instanceof Double) {
                // double
                return opDiv(convIntToDouble((Integer) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Long) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opDiv(convBigintToDouble((Long) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // bigint
                return opDiv((Long) l, convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // bigint
                return opDiv((Long) l, convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // bigint
                return opDiv((Long) l, (Long) r);
            } else if (r instanceof BigDecimal) {
                // numeric
                return opDiv(convBigintToNumeric((Long) l), (BigDecimal) r);
            } else if (r instanceof Float) {
                // float
                return opDiv(convBigintToFloat((Long) l), (Float) r);
            } else if (r instanceof Double) {
                // double
                return opDiv(convBigintToDouble((Long) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof BigDecimal) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opDiv(convNumericToDouble((BigDecimal) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // numeric
                return opDiv((BigDecimal) l, convShortToNumeric((Short) r));
            } else if (r instanceof Integer) {
                // numeric
                return opDiv((BigDecimal) l, convIntToNumeric((Integer) r));
            } else if (r instanceof Long) {
                // numeric
                return opDiv((BigDecimal) l, convBigintToNumeric((Long) r));
            } else if (r instanceof BigDecimal) {
                // numeric
                return opDiv((BigDecimal) l, (BigDecimal) r);
            } else if (r instanceof Float) {
                // double
                return opDiv(convNumericToDouble((BigDecimal) l), convFloatToDouble((Float) r));
            } else if (r instanceof Double) {
                // double
                return opDiv(convNumericToDouble((BigDecimal) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Float) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opDiv(convFloatToDouble((Float) l), convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // float
                return opDiv((Float) l, convShortToFloat((Short) r));
            } else if (r instanceof Integer) {
                // float
                return opDiv((Float) l, convIntToFloat((Integer) r));
            } else if (r instanceof Long) {
                // float
                return opDiv((Float) l, convBigintToFloat((Long) r));
            } else if (r instanceof BigDecimal) {
                // double
                return opDiv(convFloatToDouble((Float) l), convNumericToDouble((BigDecimal) r));
            } else if (r instanceof Float) {
                // float
                return opDiv((Float) l, (Float) r);
            } else if (r instanceof Double) {
                // double
                return opDiv(convFloatToDouble((Float) l), (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Double) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // double
                return opDiv((Double) l, convStringToDouble((String) r));
            } else if (r instanceof Short) {
                // double
                return opDiv((Double) l, convShortToDouble((Short) r));
            } else if (r instanceof Integer) {
                // double
                return opDiv((Double) l, convIntToDouble((Integer) r));
            } else if (r instanceof Long) {
                // double
                return opDiv((Double) l, convBigintToDouble((Long) r));
            } else if (r instanceof BigDecimal) {
                // double
                return opDiv((Double) l, convNumericToDouble((BigDecimal) r));
            } else if (r instanceof Float) {
                // double
                return opDiv((Double) l, convFloatToDouble((Float) r));
            } else if (r instanceof Double) {
                // double
                return opDiv((Double) l, (Double) r);
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Date) {
            // not applicable

        } else if (l instanceof Time) {
            // not applicable

        } else if (l instanceof Timestamp) {
            throw new PROGRAM_ERROR("left operand's type is ambiguous: TIMESTAMP or DATETIME");
        }

        throw new VALUE_ERROR(
                String.format(
                        "cannot divide two arguments due to their incompatible run-time types (%s, %s)",
                        plcsqlTypeOfJavaObject(l), plcsqlTypeOfJavaObject(r)));
    }

    private static Object opModWithRuntimeTypeConv(Object l, Object r) {
        assert l != null;
        assert r != null;

        if (l instanceof Boolean) {
            // not applicable
        } else if (l instanceof String) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // bigint
                return opMod(convStringToBigint((String) l), convStringToBigint((String) r));
            } else if (r instanceof Short) {
                // bigint
                return opMod(convStringToBigint((String) l), convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // bigint
                return opMod(convStringToBigint((String) l), convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // bigint
                return opMod(convStringToBigint((String) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // bigint
                return opMod(convStringToBigint((String) l), convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // bigint
                return opMod(convStringToBigint((String) l), convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // bigint
                return opMod(convStringToBigint((String) l), convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Short) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // bigint
                return opMod(convShortToBigint((Short) l), convStringToBigint((String) r));
            } else if (r instanceof Short) {
                // short
                return opMod((Short) l, (Short) r);
            } else if (r instanceof Integer) {
                // bigint
                return opMod(convShortToBigint((Short) l), convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // bigint
                return opMod(convShortToBigint((Short) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // bigint
                return opMod(convShortToBigint((Short) l), convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // bigint
                return opMod(convShortToBigint((Short) l), convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // bigint
                return opMod(convShortToBigint((Short) l), convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Integer) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // bigint
                return opMod(convIntToBigint((Integer) l), convStringToBigint((String) r));
            } else if (r instanceof Short) {
                // bigint
                return opMod(convIntToBigint((Integer) l), convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // int
                return opMod((Integer) l, (Integer) r);
            } else if (r instanceof Long) {
                // bigint
                return opMod(convIntToBigint((Integer) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // bigint
                return opMod(convIntToBigint((Integer) l), convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // bigint
                return opMod(convIntToBigint((Integer) l), convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // bigint
                return opMod(convIntToBigint((Integer) l), convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Long) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // bigint
                return opMod((Long) l, convStringToBigint((String) r));
            } else if (r instanceof Short) {
                // bigint
                return opMod((Long) l, convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // bigint
                return opMod((Long) l, convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // bigint
                return opMod((Long) l, (Long) r);
            } else if (r instanceof BigDecimal) {
                // bigint
                return opMod((Long) l, convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // bigint
                return opMod((Long) l, convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // bigint
                return opMod((Long) l, convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof BigDecimal) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // bigint
                return opMod(convNumericToBigint((BigDecimal) l), convStringToBigint((String) r));
            } else if (r instanceof Short) {
                // bigint
                return opMod(convNumericToBigint((BigDecimal) l), convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // bigint
                return opMod(convNumericToBigint((BigDecimal) l), convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // bigint
                return opMod(convNumericToBigint((BigDecimal) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // bigint
                return opMod(
                        convNumericToBigint((BigDecimal) l), convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // bigint
                return opMod(convNumericToBigint((BigDecimal) l), convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // bigint
                return opMod(convNumericToBigint((BigDecimal) l), convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Float) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // bigint
                return opMod(convFloatToBigint((Float) l), convStringToBigint((String) r));
            } else if (r instanceof Short) {
                // bigint
                return opMod(convFloatToBigint((Float) l), convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // bigint
                return opMod(convFloatToBigint((Float) l), convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // bigint
                return opMod(convFloatToBigint((Float) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // bigint
                return opMod(convFloatToBigint((Float) l), convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // bigint
                return opMod(convFloatToBigint((Float) l), convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // bigint
                return opMod(convFloatToBigint((Float) l), convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Double) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // bigint
                return opMod(convDoubleToBigint((Double) l), convStringToBigint((String) r));
            } else if (r instanceof Short) {
                // bigint
                return opMod(convDoubleToBigint((Double) l), convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // bigint
                return opMod(convDoubleToBigint((Double) l), convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // bigint
                return opMod(convDoubleToBigint((Double) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // bigint
                return opMod(convDoubleToBigint((Double) l), convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // bigint
                return opMod(convDoubleToBigint((Double) l), convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // bigint
                return opMod(convDoubleToBigint((Double) l), convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Date) {
            // not applicable

        } else if (l instanceof Time) {
            // not applicable

        } else if (l instanceof Timestamp) {
            throw new PROGRAM_ERROR("left operand's type is ambiguous: TIMESTAMP or DATETIME");
        }

        throw new VALUE_ERROR(
                String.format(
                        "cannot take remainder of two arguments due to their incompatible run-time types (%s, %s)",
                        plcsqlTypeOfJavaObject(l), plcsqlTypeOfJavaObject(r)));
    }

    private static Object opDivIntWithRuntimeTypeConv(Object l, Object r) {
        assert l != null;
        assert r != null;

        if (l instanceof Boolean) {
            // not applicable
        } else if (l instanceof String) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // bigint
                return opDivInt(convStringToBigint((String) l), convStringToBigint((String) r));
            } else if (r instanceof Short) {
                // bigint
                return opDivInt(convStringToBigint((String) l), convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // bigint
                return opDivInt(convStringToBigint((String) l), convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // bigint
                return opDivInt(convStringToBigint((String) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // bigint
                return opDivInt(
                        convStringToBigint((String) l), convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // bigint
                return opDivInt(convStringToBigint((String) l), convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // bigint
                return opDivInt(convStringToBigint((String) l), convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Short) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // bigint
                return opDivInt(convShortToBigint((Short) l), convStringToBigint((String) r));
            } else if (r instanceof Short) {
                // short
                return opDivInt((Short) l, (Short) r);
            } else if (r instanceof Integer) {
                // bigint
                return opDivInt(convShortToBigint((Short) l), convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // bigint
                return opDivInt(convShortToBigint((Short) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // bigint
                return opDivInt(convShortToBigint((Short) l), convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // bigint
                return opDivInt(convShortToBigint((Short) l), convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // bigint
                return opDivInt(convShortToBigint((Short) l), convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Integer) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // bigint
                return opDivInt(convIntToBigint((Integer) l), convStringToBigint((String) r));
            } else if (r instanceof Short) {
                // bigint
                return opDivInt(convIntToBigint((Integer) l), convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // int
                return opDivInt((Integer) l, (Integer) r);
            } else if (r instanceof Long) {
                // bigint
                return opDivInt(convIntToBigint((Integer) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // bigint
                return opDivInt(convIntToBigint((Integer) l), convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // bigint
                return opDivInt(convIntToBigint((Integer) l), convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // bigint
                return opDivInt(convIntToBigint((Integer) l), convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Long) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // bigint
                return opDivInt((Long) l, convStringToBigint((String) r));
            } else if (r instanceof Short) {
                // bigint
                return opDivInt((Long) l, convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // bigint
                return opDivInt((Long) l, convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // bigint
                return opDivInt((Long) l, (Long) r);
            } else if (r instanceof BigDecimal) {
                // bigint
                return opDivInt((Long) l, convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // bigint
                return opDivInt((Long) l, convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // bigint
                return opDivInt((Long) l, convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof BigDecimal) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // bigint
                return opDivInt(
                        convNumericToBigint((BigDecimal) l), convStringToBigint((String) r));
            } else if (r instanceof Short) {
                // bigint
                return opDivInt(convNumericToBigint((BigDecimal) l), convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // bigint
                return opDivInt(convNumericToBigint((BigDecimal) l), convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // bigint
                return opDivInt(convNumericToBigint((BigDecimal) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // bigint
                return opDivInt(
                        convNumericToBigint((BigDecimal) l), convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // bigint
                return opDivInt(convNumericToBigint((BigDecimal) l), convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // bigint
                return opDivInt(
                        convNumericToBigint((BigDecimal) l), convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Float) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // bigint
                return opDivInt(convFloatToBigint((Float) l), convStringToBigint((String) r));
            } else if (r instanceof Short) {
                // bigint
                return opDivInt(convFloatToBigint((Float) l), convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // bigint
                return opDivInt(convFloatToBigint((Float) l), convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // bigint
                return opDivInt(convFloatToBigint((Float) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // bigint
                return opDivInt(convFloatToBigint((Float) l), convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // bigint
                return opDivInt(convFloatToBigint((Float) l), convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // bigint
                return opDivInt(convFloatToBigint((Float) l), convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Double) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                // bigint
                return opDivInt(convDoubleToBigint((Double) l), convStringToBigint((String) r));
            } else if (r instanceof Short) {
                // bigint
                return opDivInt(convDoubleToBigint((Double) l), convShortToBigint((Short) r));
            } else if (r instanceof Integer) {
                // bigint
                return opDivInt(convDoubleToBigint((Double) l), convIntToBigint((Integer) r));
            } else if (r instanceof Long) {
                // bigint
                return opDivInt(convDoubleToBigint((Double) l), (Long) r);
            } else if (r instanceof BigDecimal) {
                // bigint
                return opDivInt(
                        convDoubleToBigint((Double) l), convNumericToBigint((BigDecimal) r));
            } else if (r instanceof Float) {
                // bigint
                return opDivInt(convDoubleToBigint((Double) l), convFloatToBigint((Float) r));
            } else if (r instanceof Double) {
                // bigint
                return opDivInt(convDoubleToBigint((Double) l), convDoubleToBigint((Double) r));
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                throw new PROGRAM_ERROR("right operand's type is ambiguous: TIMESTAMP or DATETIME");
            }

        } else if (l instanceof Date) {
            // not applicable

        } else if (l instanceof Time) {
            // not applicable

        } else if (l instanceof Timestamp) {
            throw new PROGRAM_ERROR("left operand's type is ambiguous: TIMESTAMP or DATETIME");
        }

        throw new VALUE_ERROR(
                String.format(
                        "cannot divide two arguments as integers due to their incompatible run-time types (%s, %s)",
                        plcsqlTypeOfJavaObject(l), plcsqlTypeOfJavaObject(r)));
    }

    private static int compareWithRuntimeTypeConv(Object l, Object r) {
        assert l != null;
        assert r != null;

        Comparable lConv = null;
        Comparable rConv = null;

        if (l instanceof Boolean) {
            if (r instanceof Boolean) {
                lConv = (Boolean) l;
                rConv = (Boolean) r;
            } else if (r instanceof String) {
                // not applicable
            } else if (r instanceof Short) {
                // not applicable
            } else if (r instanceof Integer) {
                // not applicable
            } else if (r instanceof Long) {
                // not applicable
            } else if (r instanceof BigDecimal) {
                // not applicable
            } else if (r instanceof Float) {
                // not applicable
            } else if (r instanceof Double) {
                // not applicable
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                // not applicable
            } else {
                throw new PROGRAM_ERROR(); // unreachable
            }

        } else if (l instanceof String) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                lConv = (String) l;
                rConv = (String) r;
            } else if (r instanceof Short) {
                lConv = convStringToDouble((String) l);
                rConv = convShortToDouble((Short) r);
            } else if (r instanceof Integer) {
                lConv = convStringToDouble((String) l);
                rConv = convIntToDouble((Integer) r);
            } else if (r instanceof Long) {
                lConv = convStringToDouble((String) l);
                rConv = convBigintToDouble((Long) r);
            } else if (r instanceof BigDecimal) {
                lConv = convStringToDouble((String) l);
                rConv = convNumericToDouble((BigDecimal) r);
            } else if (r instanceof Float) {
                lConv = convStringToDouble((String) l);
                rConv = convFloatToDouble((Float) r);
            } else if (r instanceof Double) {
                lConv = convStringToDouble((String) l);
                rConv = (Double) r;
            } else if (r instanceof Date) {
                lConv = convStringToDate((String) l);
                rConv = (Date) r;
            } else if (r instanceof Time) {
                lConv = convStringToTime((String) l);
                rConv = (Time) r;
            } else if (r instanceof Timestamp) {
                // compare as DATETIMEs
                lConv = convStringToDatetime((String) l);
                rConv = (Timestamp) r;
            } else {
                throw new PROGRAM_ERROR(); // unreachable
            }

        } else if (l instanceof Short) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                lConv = convShortToDouble((Short) l);
                rConv = convStringToDouble((String) r);
            } else if (r instanceof Short) {
                lConv = (Short) (Short) l;
                rConv = (Short) r;
            } else if (r instanceof Integer) {
                lConv = convShortToInt((Short) l);
                rConv = (Integer) r;
            } else if (r instanceof Long) {
                lConv = convShortToBigint((Short) l);
                rConv = (Long) r;
            } else if (r instanceof BigDecimal) {
                lConv = convShortToNumeric((Short) l);
                rConv = (BigDecimal) r;
            } else if (r instanceof Float) {
                lConv = convShortToFloat((Short) l);
                rConv = (Float) r;
            } else if (r instanceof Double) {
                lConv = convShortToDouble((Short) l);
                rConv = (Double) r;
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                lConv = convShortToTime((Short) l);
                rConv = (Time) r;
            } else if (r instanceof Timestamp) {
                // compare as TIMESTAMPs
                lConv = convShortToTimestamp((Short) l);
                rConv = convDatetimeToTimestamp((Timestamp) r);
            } else {
                throw new PROGRAM_ERROR(); // unreachable
            }

        } else if (l instanceof Integer) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                lConv = convIntToDouble((Integer) l);
                rConv = convStringToDouble((String) r);
            } else if (r instanceof Short) {
                lConv = (Integer) l;
                rConv = convShortToInt((Short) r);
            } else if (r instanceof Integer) {
                lConv = (Integer) l;
                rConv = (Integer) r;
            } else if (r instanceof Long) {
                lConv = convIntToBigint((Integer) l);
                rConv = (Long) r;
            } else if (r instanceof BigDecimal) {
                lConv = convIntToNumeric((Integer) l);
                rConv = (BigDecimal) r;
            } else if (r instanceof Float) {
                lConv = convIntToFloat((Integer) l);
                rConv = (Float) r;
            } else if (r instanceof Double) {
                lConv = convIntToDouble((Integer) l);
                rConv = (Double) r;
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                lConv = convIntToTime((Integer) l);
                rConv = (Time) r;
            } else if (r instanceof Timestamp) {
                // compare as TIMESTAMPs
                lConv = convIntToTimestamp((Integer) l);
                rConv = convDatetimeToTimestamp((Timestamp) r);
            } else {
                throw new PROGRAM_ERROR(); // unreachable
            }

        } else if (l instanceof Long) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                lConv = convBigintToDouble((Long) l);
                rConv = convStringToDouble((String) r);
            } else if (r instanceof Short) {
                lConv = (Long) l;
                rConv = convShortToBigint((Short) r);
            } else if (r instanceof Integer) {
                lConv = (Long) l;
                rConv = convIntToBigint((Integer) r);
            } else if (r instanceof Long) {
                lConv = (Long) l;
                rConv = (Long) r;
            } else if (r instanceof BigDecimal) {
                lConv = convBigintToNumeric((Long) l);
                rConv = (BigDecimal) r;
            } else if (r instanceof Float) {
                lConv = convBigintToFloat((Long) l);
                rConv = (Float) r;
            } else if (r instanceof Double) {
                lConv = convBigintToDouble((Long) l);
                rConv = (Double) r;
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                lConv = convBigintToTime((Long) l);
                rConv = (Time) r;
            } else if (r instanceof Timestamp) {
                // compare as TIMESTAMPs
                lConv = convBigintToTimestamp((Long) l);
                rConv = convDatetimeToTimestamp((Timestamp) r);
            } else {
                throw new PROGRAM_ERROR(); // unreachable
            }

        } else if (l instanceof BigDecimal) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                lConv = convNumericToDouble((BigDecimal) l);
                rConv = convStringToDouble((String) r);
            } else if (r instanceof Short) {
                lConv = (BigDecimal) l;
                rConv = convShortToNumeric((Short) r);
            } else if (r instanceof Integer) {
                lConv = (BigDecimal) l;
                rConv = convIntToNumeric((Integer) r);
            } else if (r instanceof Long) {
                lConv = (BigDecimal) l;
                rConv = convBigintToNumeric((Long) r);
            } else if (r instanceof BigDecimal) {
                lConv = (BigDecimal) l;
                rConv = (BigDecimal) r;
            } else if (r instanceof Float) {
                lConv = convNumericToDouble((BigDecimal) l);
                rConv = convFloatToDouble((Float) r);
            } else if (r instanceof Double) {
                lConv = convNumericToDouble((BigDecimal) l);
                rConv = (Double) r;
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                // not applicable
            } else {
                throw new PROGRAM_ERROR(); // unreachable
            }

        } else if (l instanceof Float) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                lConv = convFloatToDouble((Float) l);
                rConv = convStringToDouble((String) r);
            } else if (r instanceof Short) {
                lConv = (Float) l;
                rConv = convShortToFloat((Short) r);
            } else if (r instanceof Integer) {
                lConv = (Float) l;
                rConv = convIntToFloat((Integer) r);
            } else if (r instanceof Long) {
                lConv = (Float) l;
                rConv = convBigintToFloat((Long) r);
            } else if (r instanceof BigDecimal) {
                lConv = convFloatToDouble((Float) l);
                rConv = convNumericToDouble((BigDecimal) r);
            } else if (r instanceof Float) {
                lConv = (Float) l;
                rConv = (Float) r;
            } else if (r instanceof Double) {
                lConv = convFloatToDouble((Float) l);
                rConv = (Double) r;
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                // not applicable
            } else {
                throw new PROGRAM_ERROR(); // unreachable
            }

        } else if (l instanceof Double) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                lConv = (Double) l;
                rConv = convStringToDouble((String) r);
            } else if (r instanceof Short) {
                lConv = (Double) l;
                rConv = convShortToDouble((Short) r);
            } else if (r instanceof Integer) {
                lConv = (Double) l;
                rConv = convIntToDouble((Integer) r);
            } else if (r instanceof Long) {
                lConv = (Double) l;
                rConv = convBigintToDouble((Long) r);
            } else if (r instanceof BigDecimal) {
                lConv = (Double) l;
                rConv = convNumericToDouble((BigDecimal) r);
            } else if (r instanceof Float) {
                lConv = (Double) l;
                rConv = convFloatToDouble((Float) r);
            } else if (r instanceof Double) {
                lConv = (Double) l;
                rConv = (Double) r;
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                // not applicable
            } else {
                throw new PROGRAM_ERROR(); // unreachable
            }

        } else if (l instanceof Date) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                lConv = (Date) l;
                rConv = convStringToDate((String) r);
            } else if (r instanceof Short) {
                // not applicable
            } else if (r instanceof Integer) {
                // not applicable
            } else if (r instanceof Long) {
                // not applicable
            } else if (r instanceof BigDecimal) {
                // not applicable
            } else if (r instanceof Float) {
                // not applicable
            } else if (r instanceof Double) {
                // not applicable
            } else if (r instanceof Date) {
                lConv = (Date) l;
                rConv = (Date) r;
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                // compare as DATETIMEs
                lConv = convDateToDatetime((Date) l);
                rConv = (Timestamp) r;
            } else {
                throw new PROGRAM_ERROR(); // unreachable
            }

        } else if (l instanceof Time) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                lConv = (Time) l;
                rConv = convStringToTime((String) r);
            } else if (r instanceof Short) {
                lConv = (Time) l;
                rConv = convShortToTime((Short) r);
            } else if (r instanceof Integer) {
                lConv = (Time) l;
                rConv = convIntToTime((Integer) r);
            } else if (r instanceof Long) {
                lConv = (Time) l;
                rConv = convBigintToTime((Long) r);
            } else if (r instanceof BigDecimal) {
                // not applicable
            } else if (r instanceof Float) {
                // not applicable
            } else if (r instanceof Double) {
                // not applicable
            } else if (r instanceof Date) {
                // not applicable
            } else if (r instanceof Time) {
                lConv = (Time) l;
                rConv = (Time) r;
            } else if (r instanceof Timestamp) {
                // not applicable
            } else {
                throw new PROGRAM_ERROR(); // unreachable
            }

        } else if (l instanceof Timestamp) {

            if (r instanceof Boolean) {
                // not applicable
            } else if (r instanceof String) {
                lConv = (Timestamp) l;
                rConv = convStringToDatetime((String) r);
            } else if (r instanceof Short) {
                // l must be a TIMESTAMP
                lConv = convDatetimeToTimestamp((Timestamp) l);
                rConv = convShortToTimestamp((Short) r);
            } else if (r instanceof Integer) {
                // l must be a TIMESTAMP
                lConv = convDatetimeToTimestamp((Timestamp) l);
                rConv = convIntToTimestamp((Integer) r);
            } else if (r instanceof Long) {
                // l must be a TIMESTAMP
                lConv = convDatetimeToTimestamp((Timestamp) l);
                rConv = convBigintToTimestamp((Long) r);
            } else if (r instanceof BigDecimal) {
                // not applicable
            } else if (r instanceof Float) {
                // not applicable
            } else if (r instanceof Double) {
                // not applicable
            } else if (r instanceof Date) {
                lConv = (Timestamp) l;
                rConv = convDateToDatetime((Date) r);
            } else if (r instanceof Time) {
                // not applicable
            } else if (r instanceof Timestamp) {
                // compare as DATETIMEs
                lConv = (Timestamp) l;
                rConv = (Timestamp) r;
            } else {
                throw new PROGRAM_ERROR(); // unreachable
            }

        } else {
            throw new PROGRAM_ERROR(); // unreachable
        }

        if (lConv == null) {
            assert rConv == null;
            throw new VALUE_ERROR(
                    "imcomparable types"); // cannot compare two values of unsupported types
        } else {
            assert rConv != null;
            return lConv.compareTo(rConv);
        }
    }

    private static String getHostVarsStr(int len) {
        if (len == 0) {
            return "()";
        } else {
            String[] arr = new String[len];
            Arrays.fill(arr, "?");
            return "(" + String.join(", ", arr) + ")";
        }
    }

    private static boolean isEmptyStr(String s) {
        return s == null || s.length() == 0;
    }

    private static String detachTrailingZeros(String f) {
        if (f.indexOf('.') < 0) {
            // f does not represent a floating point number
            return f;
        }

        int len = f.length();
        for (int i = len - 1; i >= 0; i--) {
            char c = f.charAt(i);

            if (c == '.') {
                return f.substring(0, i);
            }

            if (c != '0') {
                return f.substring(0, i + 1);
            }
        }

        assert false; // unreachable
        return null;
    }

    private static String plcsqlTypeOfJavaObject(Object o) {
        assert o != null;
        Class<?> c = o.getClass();
        if (c == Boolean.class) {
            return "BOOLEAN";
        } else if (c == String.class) {
            return "STRING";
        } else if (c == Short.class) {
            return "SHORT";
        } else if (c == Integer.class) {
            return "INT";
        } else if (c == Long.class) {
            return "BIGINT";
        } else if (c == BigDecimal.class) {
            return "NUMERIC";
        } else if (c == Float.class) {
            return "FLOAT";
        } else if (c == Double.class) {
            return "DOUBLE";
        } else if (c == Date.class) {
            return "DATE";
        } else if (c == Time.class) {
            return "TIME";
        } else if (c == Timestamp.class) {
            return "TIMESTAMP or DATETIME (ambiguous)";
        } else {
            return "<unknown>";
        }
    }
}
