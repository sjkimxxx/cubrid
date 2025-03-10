/*
 *
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

package com.cubrid.jsp.code;

import com.cubrid.jsp.Server;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import javax.tools.SimpleJavaFileObject;

public class CompiledCode extends SimpleJavaFileObject {
    private String className = null;
    private ByteArrayOutputStream baos = null;

    private byte[] byteCode = null;

    public CompiledCode(String className) throws java.net.URISyntaxException {
        super(new URI(className), Kind.CLASS);

        int idx = className.indexOf(".");
        if (idx != -1) {
            this.className = className.substring(0, idx);

        } else {
            this.className = className;
        }
        this.baos = new ByteArrayOutputStream();
    }

    public String getClassName() {
        return className;
    }

    public String getClassNameWithExtention() {
        return className + ".class";
    }

    public byte[] getByteCode() {
        if (byteCode == null) {
            byteCode = baos.toByteArray();
            try {
                baos.close();
            } catch (IOException e) {
                // ignore
            }
        }
        return byteCode;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return new String(getByteCode(), Server.getConfig().getServerCharset());
    }

    @Override
    public OutputStream openOutputStream() throws IOException {
        return baos;
    }
}
