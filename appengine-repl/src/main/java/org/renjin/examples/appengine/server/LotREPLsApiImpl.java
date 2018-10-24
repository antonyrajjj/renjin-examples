/*
 * Copyright 2009 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.renjin.examples.appengine.server;

import com.google.gwt.user.server.rpc.RemoteServiceServlet;
import org.renjin.appengine.AppEngineContextFactory;
import org.renjin.eval.Context;
import org.renjin.eval.EvalException;
import org.renjin.eval.Session;
import org.renjin.examples.appengine.shared.InterpreterException;
import org.renjin.examples.appengine.shared.LotREPLsApi;
import org.renjin.parser.RParser;
import org.renjin.primitives.io.serialization.RDataReader;
import org.renjin.primitives.io.serialization.RDataWriter;
import org.renjin.sexp.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server-side implementation of LotREPLsApi. This class manages the
 * interpreters for each language and routes commands appropriately.
 *
 */
public class LotREPLsApiImpl extends RemoteServiceServlet implements LotREPLsApi {
  private static final String GLOBALS = "GLOBALS";
  private static final Logger LOGGER = Logger.getLogger(LotREPLsApiImpl.class.getName());

  private ThreadLocal<Session> sessionThreadLocal = new InheritableThreadLocal<>();

  @Override
  public void init(ServletConfig config) throws ServletException {
    super.init(config);
  }


  public String eval(String script)
      throws InterpreterException {

    // Parse the command into an AST
    ExpressionVector expression = RParser.parseSource(script + "\n");
    if(expression == null) {
      return "";
    }

    // Get our thread local session
    Session session = sessionThreadLocal.get();
    if(session == null) {
      LOGGER.info("Creating new session for thread " + Thread.currentThread().getId());
      session = AppEngineContextFactory.createSession(getServletContext());
      sessionThreadLocal.set(session);
    }

    // Restore the globals for this session
    restoreGlobals(session.getTopLevelContext());

    StringWriter writer = new StringWriter();
    session.setStdOut(new PrintWriter(writer));

    SEXP result;
    try {
      result = session.getTopLevelContext().evaluate(expression);
    } catch(EvalException e) {
      LOGGER.log(Level.WARNING, "Evaluation failed", e);
      throw new InterpreterException(e.getMessage());
    }

    // If the result isn't invisible, print the result
    if(!session.isInvisible()) {
      session.getTopLevelContext().evaluate(FunctionCall.newCall(Symbol.get("print"), result));
    }

    saveGlobals(session.getTopLevelContext());

    session.getStdOut().flush();

    return writer.toString();

  }

  private void restoreGlobals(Context context) {
    HttpServletRequest request = getThreadLocalRequest();
    HttpSession session = request.getSession();
    byte[] globals = null;
    try {
      globals = (byte[]) session.getAttribute(GLOBALS);
      if (globals != null) {

        RDataReader reader = new RDataReader(context, new ByteArrayInputStream(globals));
        PairList list = (PairList) reader.readFile();
        for(PairList.Node node : list.nodes()) {
          context.getGlobalEnvironment().setVariable(node.getTag(), node.getValue());
        }
      }
    } catch(Exception e) {
      // If there was a deserialization error, throw the session away
      session.removeAttribute(GLOBALS);
      LOGGER.log(Level.WARNING, "Could not deserialize context.", e);
    }
  }

  private void saveGlobals(Context context) {
    try {
      PairList.Builder list = new PairList.Builder();
      Environment globalEnvironment = context.getGlobalEnvironment();

      int count =0;
      for(Symbol symbol : globalEnvironment.getSymbolNames()) {
        SEXP value = globalEnvironment.getVariable(symbol);
        if(value instanceof Vector) {
          list.add(symbol, value);
          count++;
        }
      }
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      RDataWriter writer = new RDataWriter(context, baos);
      writer.serialize(list.build());

      LOGGER.severe(count + " variable saved, " + baos.toByteArray().length + " bytes");

      HttpServletRequest request = getThreadLocalRequest();
      HttpSession session = request.getSession();
      session.setAttribute(GLOBALS, baos.toByteArray());

    } catch(Exception e) {
      LOGGER.log(Level.WARNING, "Failed to serialize globals", e);
    }
  }
}
