package io.hawt.web.plugin.karaf.terminal;

import org.apache.felix.service.command.CommandProcessor;
import org.apache.felix.service.command.CommandSession;
import org.apache.karaf.shell.console.jline.Console;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.zip.GZIPOutputStream;


/**
 * @author Stan Lewis
 */
public class TerminalServlet extends HttpServlet {

    public static final int TERM_WIDTH = 120;
    public static final int TERM_HEIGHT = 39;
    private final static Logger LOG = LoggerFactory.getLogger(TerminalServlet.class);
    /**
     * Pseudo class version ID to keep the IDE quite.
     */
    private static final long serialVersionUID = 1L;

    public CommandProcessor getCommandProcessor() {
        return CommandProcessorHolder.getCommandProcessor();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String encoding = request.getHeader("Accept-Encoding");
        boolean supportsGzip = (encoding != null && encoding.toLowerCase().indexOf("gzip") > -1);
        SessionTerminal st = (SessionTerminal) request.getSession(true).getAttribute("terminal");
        if (st == null || st.isClosed()) {
            st = new SessionTerminal(getCommandProcessor());
            request.getSession().setAttribute("terminal", st);
        }
        String str = request.getParameter("k");
        String f = request.getParameter("f");
        String dump = st.handle(str, f != null && f.length() > 0);
        if (dump != null) {
            if (supportsGzip) {
                response.setHeader("Content-Encoding", "gzip");
                response.setHeader("Content-Type", "text/html");
                try {
                    GZIPOutputStream gzos = new GZIPOutputStream(response.getOutputStream());
                    gzos.write(dump.getBytes());
                    gzos.close();
                } catch (IOException ie) {
                    // handle the error here
                    ie.printStackTrace();
                }
            } else {
                response.getOutputStream().write(dump.getBytes());
            }
        }
    }

    public class SessionTerminal implements Runnable {

        private Terminal terminal;
        private Console console;
        private PipedOutputStream in;
        private PipedInputStream out;
        private boolean closed;

        public SessionTerminal(CommandProcessor commandProcessor) throws IOException {
            try {
                this.terminal = new Terminal(TERM_WIDTH, TERM_HEIGHT);
                terminal.write("\u001b\u005B20\u0068"); // set newline mode on

                in = new PipedOutputStream();
                out = new PipedInputStream();
                PrintStream pipedOut = new PrintStream(new PipedOutputStream(out), true);

                console = new Console(commandProcessor,
                        new PipedInputStream(in),
                        pipedOut,
                        pipedOut,
                        new WebTerminal(TERM_WIDTH, TERM_HEIGHT),
                        null,
                        null);
                CommandSession session = console.getSession();
                session.put("APPLICATION", System.getProperty("karaf.name", "root"));
                session.put("USER", "karaf");
                session.put("COLUMNS", Integer.toString(TERM_WIDTH));
                session.put("LINES", Integer.toString(TERM_HEIGHT));
            } catch (IOException e) {
                //e.printStackTrace();
                throw e;
            } catch (Exception e) {
                //e.printStackTrace();
                throw (IOException) new IOException().initCause(e);
            }
            new Thread(console).start();
            new Thread(this).start();
        }

        public boolean isClosed() {
            return closed;
        }

        public String handle(String str, boolean forceDump) throws IOException {
            try {
                if (str != null && str.length() > 0) {
                    String d = terminal.pipe(str);
                    for (byte b : d.getBytes()) {
                        in.write(b);
                    }
                    in.flush();
                }
            } catch (IOException e) {
                closed = true;
                throw e;
            }
            try {
                return terminal.dump(10, forceDump);
            } catch (InterruptedException e) {
                throw new InterruptedIOException(e.toString());
            }
        }

        public void run() {
            try {
                for (; ; ) {
                    byte[] buf = new byte[8192];
                    int l = out.read(buf);
                    InputStreamReader r = new InputStreamReader(new ByteArrayInputStream(buf, 0, l));
                    StringBuilder sb = new StringBuilder();
                    for (; ; ) {
                        int c = r.read();
                        if (c == -1) {
                            break;
                        }
                        sb.append((char) c);
                    }
                    if (sb.length() > 0) {
                        terminal.write(sb.toString());
                    }
                    String s = terminal.read();
                    if (s != null && s.length() > 0) {
                        for (byte b : s.getBytes()) {
                            in.write(b);
                        }
                    }
                }
            } catch (IOException e) {
                closed = true;
                e.printStackTrace();
            }
        }

    }
}
