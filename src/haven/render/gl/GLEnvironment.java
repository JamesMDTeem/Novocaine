/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven.render.gl;

import java.util.*;
import java.util.function.*;
import java.nio.ByteBuffer;
import haven.*;
import haven.render.*;
import haven.render.sl.*;
import static haven.render.DataBuffer.Usage.*;

public abstract class GLEnvironment implements Environment {
    public static final boolean debuglog = Utils.nonconst(false), labels = Utils.nonconst(false);
    public final Caps caps;
    public int nilfbo_id = 0, nilfbo_db = 0;
    final Object drawmon = new Object();
    final Object prepmon = new Object();
    final Collection<GLObject> disposed = new LinkedList<>();
    final List<GLQuery> queries = new LinkedList<>(); // Synchronized on drawmon
    final Queue<Runnable> callbacks = new LinkedList<>();
    Thread cbthread = null;
    final Queue<GLRender> submitted = new LinkedList<>();
    Area wnd;
    private GLRender prep = null;
    private Applier curstate = new Applier(this);
    private boolean invalid = false;

    public static class HardwareException extends UnavailableException {
	public final Caps caps;

	public HardwareException(String msg, Caps caps) {
	    super(msg);
	    this.caps = caps;
	}
    }

    public static class Caps implements java.io.Serializable, Environment.Caps {
	private static final java.util.regex.Pattern slvp = java.util.regex.Pattern.compile("^(\\d+)\\.(\\d+)");
	public final String vendor, version, renderer;
	public final int major, minor, glslver;
	public final Collection<String> exts;
	public final int maxtargets;
	public final float anisotropy;
	public final float linemin, linemax;

	public static int glgeti(GL gl, int param) {
	    int[] buf = {0};
	    gl.glGetIntegerv(param, buf);
	    GLException.checkfor(gl, null);
	    return(buf[0]);
	}

	public static int glcondi(GL gl, int param, int def) {
	    GLException.checkfor(gl, null);
	    int[] buf = {0};
	    gl.glGetIntegerv(param, buf);
	    if(gl.glGetError() != 0)
		return(def);
	    return(buf[0]);
	}

	public static float glgetf(GL gl, int param) {
	    float[] buf = {0};
	    gl.glGetFloatv(param, buf);
	    GLException.checkfor(gl, null);
	    return(buf[0]);
	}

	public static String glconds(GL gl, int param) {
	    GLException.checkfor(gl, null);
	    String ret = gl.glGetString(param);
	    if(gl.glGetError() != 0)
		return(null);
	    return(ret);
	}

	public Caps(GL gl) {
	    {
		int major, minor;
		try {
		    major = glgeti(gl, GL.GL_MAJOR_VERSION);
		    minor = glgeti(gl, GL.GL_MINOR_VERSION);
		} catch(GLException e) {
		    major = 1;
		    minor = 0;
		}
		this.major = major; this.minor = minor;
	    }
	    this.vendor = gl.glGetString(GL.GL_VENDOR);
	    this.version = gl.glGetString(GL.GL_VERSION);
	    this.renderer = gl.glGetString(GL.GL_RENDERER);
	    if(major >= 3) {
		this.exts = new ArrayList<>();
		for(int i = 0, n = glgeti(gl, GL.GL_NUM_EXTENSIONS); i < n; i++)
		    this.exts.add(gl.glGetStringi(GL.GL_EXTENSIONS, i));
	    } else {
		this.exts = Arrays.asList(gl.glGetString(GL.GL_EXTENSIONS).split(" "));
	    }
	    this.maxtargets = glcondi(gl, GL.GL_MAX_COLOR_ATTACHMENTS, 1);
	    {
		int glslver = 0;
		String slv = glconds(gl, GL.GL_SHADING_LANGUAGE_VERSION);
		if(slv != null) {
		    java.util.regex.Matcher m = slvp.matcher(slv);
		    if(m.find()) {
			try {
			    int major = Integer.parseInt(m.group(1));
			    int minor = Integer.parseInt(m.group(2));
			    if((major > 0) && (major < 256) && (minor >= 0) && (minor < 256))
				glslver = (major << 8) | minor;
			} catch(NumberFormatException e) {
			}
		    }
		}
		this.glslver = glslver;
	    }
	    if(exts.contains("GL_EXT_texture_filter_anisotropic"))
		anisotropy = glgetf(gl, GL.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
	    else
		anisotropy = 0;
	    {
		float[] buf = {0, 0};
		gl.glGetFloatv(GL.GL_ALIASED_LINE_WIDTH_RANGE, buf);
		if(gl.glGetError() == 0) {
		    this.linemin = buf[0];
		    this.linemax = buf[1];
		} else {
		    this.linemin = this.linemax = 1;
		}
	    }
	}

	public void checkreq() {
	    if(major < 3)
		throw(new HardwareException("Graphics context does not support OpenGL 3.0.", this));
	}

	public String vendor() {return(vendor);}
	public String driver() {return("OpenGL (" + version + ")");}
	public String device() {return(renderer);}
    }

    static enum MemStats {
	INDICES, VERTICES, TEXTURES, VAOS, FBOS
    }
    final int[] stats_obj = new int[MemStats.values().length];
    final long[] stats_mem = new long[MemStats.values().length];

    protected abstract Caps mkcaps(GL initgl);

    public GLEnvironment(GL initgl, Area wnd) {
	this.wnd = wnd;
	this.caps = mkcaps(initgl);
	this.caps.checkreq();
	initialize(initgl);
    }

    private void initialize(GL gl) {
	if(debuglog) {
	    gl.glEnable(GL.GL_DEBUG_OUTPUT);
	    gl.glDebugMessageControl(GL.GL_DONT_CARE, GL.GL_DONT_CARE, GL.GL_DONT_CARE, 0, new int[0], true);
	    /* gl.glDebugMessageControl(GL3.GL_DEBUG_SOURCE_API, GL3.GL_DEBUG_TYPE_OTHER, GL3.GL_DONT_CARE, 1, new int[] {131185}, 0, false); */
	}
	gl.glEnable(GL.GL_PROGRAM_POINT_SIZE);
    }

    public GLRender render() {
	return(new GLRender(this));
    }

    public GLDrawList drawlist() {
	return(new GLDrawList(this));
    }

    public void reshape(Area wnd) {
	this.wnd = wnd;
    }

    public Area shape() {
	return(wnd);
    }

    private void ckcbt() {
	synchronized(callbacks) {
	    if(!callbacks.isEmpty() && (cbthread == null)) {
		cbthread = new HackThread(this::cbloop, "Render-query callback thread");
		cbthread.setDaemon(true);
		cbthread.start();
	    }
	}
    }

    private void cbloop() {
	try {
	    double last = Utils.rtime(), now = last;
	    while(true) {
		Runnable cb;
		synchronized(callbacks) {
		    while(callbacks.isEmpty()) {
			if(now - last >= 5) {
			    cbthread = null;
			    return;
			}
			callbacks.wait((int)((last + 6 - now) * 1000));
			now = Utils.rtime();
		    }
		    cb = callbacks.remove();
		    last = now;
		}
		cb.run();
	    }
	} catch(InterruptedException e) {
	} finally {
	    synchronized(callbacks) {
		if(cbthread == Thread.currentThread())
		    cbthread = null;
		ckcbt();
	    }
	}
    }

    void callback(Runnable cb) {
	synchronized(callbacks) {
	    callbacks.add(cb);
	    callbacks.notifyAll();
	    ckcbt();
	}
    }

    public void synccallbacks() throws InterruptedException {
	boolean[] done = {false};
	callback(() -> {
		synchronized(done) {
		    done[0] = true;
		    done.notifyAll();
		}
	    });
	synchronized(done) {
	    while(!done[0])
		done.wait();
	}
    }

    private void checkqueries(GL gl) {
	for(Iterator<GLQuery> i = queries.iterator(); i.hasNext();) {
	    GLQuery query = i.next();
	    if(!query.check(gl))
		continue;
	    query.dispose();
	    i.remove();
	}
    }

    public static class DebugMessage {
	public final int src, type, id, sev;
	public final String msg;

	public DebugMessage(int src, int type, int id, int sev, String msg) {
	    this.src = src;
	    this.type = type;
	    this.id = id;
	    this.sev = sev;
	    this.msg = msg;
	}
    }

    private List<DebugMessage> getdebuglog(GL gl) {
	List<DebugMessage> ret = new ArrayList<>();
	int n = 16;
	int[] src = new int[n], type = new int[n], id = new int[n], sev = new int[n], len = new int[n];
	while(true) {
	    int nlen = Caps.glgeti(gl, GL.GL_DEBUG_NEXT_LOGGED_MESSAGE_LENGTH);
	    byte[] buf = new byte[Math.max(nlen, 128) * n];
	    int rv = gl.glGetDebugMessageLog(n, buf.length, src, type, id, sev, len, buf);
	    if(rv == 0)
		break;
	    for(int i = 0, p = 0; i < rv; p += len[i++])
		ret.add(new DebugMessage(src[i], type[i], id[i], sev[i], new String(buf, p, len[i] - 1)));
	}
	return(ret);
    }

    private void checkdebuglog(GL gl) {
	boolean f = false;
	for(DebugMessage msg : getdebuglog(gl)) {
	    if(msg.src == GL.GL_DEBUG_SOURCE_APPLICATION)
		continue;
	    System.err.printf("%d %d %d %d -- %s\n", msg.src, msg.type, msg.id, msg.sev, msg.msg);
	    f = true;
	}
	if(f)
	    System.err.println();
    }

    public void process(GL gl) {
	GLRender prep;
	Collection<GLRender> copy;
	synchronized(submitted) {
	    /* It is important to fetch the submitted renders before
	     * prep, so that additional once aren't submitted during
	     * processing that haven't been prepared. */
	    copy = new ArrayList<>(submitted);
	    submitted.clear();
	}
	synchronized(prepmon) {
	    prep = this.prep;
	    this.prep = null;
	}
	try {
	    synchronized(drawmon) {
		checkqueries(gl);
		if((prep != null) && (prep.gl != null)) {
		    BufferBGL xf = new BufferBGL(16);
		    this.curstate.apply(xf, prep.init);
		    xf.run(gl);
		    prep.gl.run(gl);
		    this.curstate = prep.state;
		    try {
			GLException.checkfor(gl, this);
		    } catch(Exception exc) {
			throw(new BGL.BGLException(prep.gl, null, exc));
		    }
		    prep.dispose();
		}
		for(GLRender cmd : copy) {
		    BufferBGL xf = new BufferBGL(16);
		    this.curstate.apply(xf, cmd.init);
		    xf.run(gl);
		    cmd.gl.run(gl);
		    this.curstate = cmd.state;
		    try {
			GLException.checkfor(gl, this);
		    } catch(Exception exc) {
			throw(new BGL.BGLException(cmd.gl, null, exc));
		    }
		    cmd.dispose();
		}
		checkqueries(gl);
		disposeall().run(gl);
		clean();
		if(debuglog)
		    checkdebuglog(gl);
	    }
	} catch(Exception e) {
	    for(Throwable c = e; c != null; c = c.getCause()) {
		if(c instanceof GLException)
		    ((GLException)c).initenv(this);
	    }
	    throw(e);
	}
    }

    public void finish(GL gl) throws InterruptedException {
	synchronized(drawmon) {
	    gl.glFinish();
	    checkqueries(gl);
	    if(!queries.isEmpty())
		throw(new AssertionError("active queries left after glFinish"));
	    synccallbacks();
	}
    }

    public void submit(Render cmd) {
	if(!(cmd instanceof GLRender))
	    throw(new IllegalArgumentException("environment mismatch"));
	GLRender gcmd = (GLRender)cmd;
	if(gcmd.env != this)
	    throw(new IllegalArgumentException("environment mismatch"));
	boolean inv;
	synchronized(submitted) {
	    inv = invalid;
	    if(gcmd.gl != null) {
		if(!inv) {
		    submitted.add(gcmd);
		    submitted.notifyAll();
		} else {
		    gcmd.gl.abort();
		}
	    } else {
		gcmd.dispose();
	    }
	}
	if(inv)
	    gcmd.dispose();
    }

    public void submitwait() throws InterruptedException {
	synchronized(submitted) {
	    while(submitted.peek() == null)
		submitted.wait();
	}
    }

    public static volatile int cachedDisposeCap = Utils.getprefi("perf.gl_dispose_per_frame", 64);

    private BufferBGL disposeall() {
	int tail;
	synchronized(seqmon) {
	    tail = seqtail;
	}
	BufferBGL buf = new BufferBGL();
	Collection<GLObject> copy;
	int cap = cachedDisposeCap;
	synchronized(disposed) {
	    if(disposed.isEmpty())
		return(buf);
	    /* Cap the number of GL deletes per frame to smooth out the driver-side stall when
	     * many resources go down at once.
	     *
	     * The drain rate rises with the backlog rather than switching off at a threshold.
	     * The old rule - cap normally, but Integer.MAX_VALUE once pending passed 8x cap -
	     * had the valve backwards: a small backlog, which would not have stalled anything,
	     * was throttled, while the big teardown the cap exists for was the one case that
	     * got deleted all in one frame. That is the shape of the multi-hundred-millisecond
	     * draw spikes in vmem.log.
	     *
	     * Proportional draining still bounds the queue - at pending/8 per frame a backlog
	     * falls off geometrically, so it cannot run away - without ever handing the driver
	     * the whole pile at once. */
	    int pendingTotal = disposed.size();
	    int effectiveCap = (cap > 0) ? Math.max(cap, pendingTotal / 8) : Integer.MAX_VALUE;
	    copy = new ArrayList<>(Math.min(pendingTotal, effectiveCap));
	    int taken = 0;
	    for(Iterator<GLObject> i = disposed.iterator(); i.hasNext();) {
		if(taken >= effectiveCap)
		    break;
		GLObject obj = i.next();
		if(obj.dispseq - tail > 0)
		    break;
		copy.add(obj);
		i.remove();
		taken++;
	    }
	}
	for(GLObject obj : copy)
	    buf.bglDelete(obj);
	buf.bglCheckErr();
	return(buf);
    }

    public abstract SysBuffer malloc(int sz);
    public abstract SysBuffer subsume(ByteBuffer data, int sz);

    public FillBuffer fillbuf(DataBuffer tgt, int from, int to) {
	if((from == 0) && (to == tgt.size())) {
	    StreamBuffer stb;
	    if((tgt instanceof VertexArray.Buffer) && ((stb = GLReference.get(((VertexArray.Buffer)tgt).ro, StreamBuffer.class)) != null))
		return(stb.new Fill());
	    if((tgt instanceof Model.Indices) && ((stb = GLReference.get(((Model.Indices)tgt).ro, StreamBuffer.class)) != null))
		return(stb.new Fill());
	}
	return(new FillBuffers.Array(this, to - from));
    }

    public FillBuffer fillbuf(DataBuffer target) {
	if(target instanceof Texture.Image) {
	    /* XXX: This seems to be a buf with JOGL and buffer-space
	     * checking for mip-mapped 3D textures. This should be
	     * entirely unnecessary. */
	    Texture.Image<?> img = (Texture.Image<?>)target;
	    if(img.tex instanceof Texture3D) {
		if(img.size() < 14)
		    return(fillbuf(target, 0, 14));
	    }
	}
	return(Environment.super.fillbuf(target));
    }

    GLRender prepare() {
	if(prep == null)
	    prep = new GLRender(this);
	return(prep);
    }
    void prepare(GLObject obj) {
	synchronized(prepmon) {
	    prepare().gl().bglCreate(obj);
	}
    }
    void prepare(BGL.Request req) {
	synchronized(prepmon) {
	    prepare().gl().bglSubmit(req);
	}
    }
    void prepare(Consumer<GLRender> func) {
	synchronized(prepmon) {
	    func.accept(prepare());
	}
    }

    Disposable prepare(Model.Indices buf) {
	synchronized(buf) {
	    switch(buf.usage) {
	    case EPHEMERAL: {
		if(!(buf.ro instanceof HeapBuffer)) {
		    if(buf.ro != null)
			buf.ro.dispose();
		    buf.ro = new HeapBuffer(this, buf, buf.init);
		}
		return(buf.ro);
	    }
	    case STREAM: {
		StreamBuffer ret;
		if(((ret = GLReference.get(buf.ro, StreamBuffer.class)) == null) || (ret.rbuf.env != this)) {
		    if(buf.ro != null)
			buf.ro.dispose();
		    buf.ro = new GLReference<>(ret = new StreamBuffer(this, buf.size()));
		    StreamBuffer.Fill data = (buf.init == null) ? null : (StreamBuffer.Fill)buf.init.fill(buf, this);
		    StreamBuffer jdret = ret;
		    GLBuffer rbuf = ret.rbuf;
		    prepare((GLRender g) -> {
			    BGL gl = g.gl();
			    Vao0State.apply(this, gl, g.state, rbuf);
			    if(data == null) {
				gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, buf.size(), null, GL.GL_DYNAMIC_DRAW);
			    } else {
				ByteBuffer xfbuf = data.get();
				gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, buf.size(), xfbuf, GL.GL_DYNAMIC_DRAW);
				jdret.put(gl, xfbuf);
			    }
			    if(labels && (buf.desc != null))
				gl.glObjectLabel(GL.GL_BUFFER, rbuf, String.valueOf(buf.desc));
			    rbuf.setmem(MemStats.INDICES, buf.size());
			});
		}
		return(ret);
	    }
	    case STATIC: {
		GLBuffer ret;
		if(((ret = GLReference.get(buf.ro, GLBuffer.class)) == null) || (ret.env != this)) {
		    if(buf.ro != null)
			buf.ro.dispose();
		    buf.ro = new GLReference<>(ret = new GLBuffer(this));
		    FillBuffers.Array data = (buf.init == null) ? null : (FillBuffers.Array)buf.init.fill(buf, this);
		    GLBuffer jdret = ret;
		    prepare((GLRender g) -> {
			    BGL gl = g.gl();
			    Vao0State.apply(this, gl, g.state, jdret);
			    gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, buf.size(), (data == null) ? null : data.data(), GL.GL_STATIC_DRAW);
			    if(labels && (buf.desc != null))
				gl.glObjectLabel(GL.GL_BUFFER, jdret, String.valueOf(buf.desc));
			    jdret.setmem(MemStats.INDICES, buf.size());
			    if(data != null) data.dispose();
			});
		}
		return(ret);
	    }
	    default:
		throw(new Error());
	    }
	}
    }
    Disposable prepare(VertexArray.Buffer buf) {
	synchronized(buf) {
	    switch(buf.usage) {
	    case EPHEMERAL: {
		if(!(buf.ro instanceof HeapBuffer)) {
		    if(buf.ro != null)
			buf.ro.dispose();
		    buf.ro = new HeapBuffer(this, buf, buf.init);
		}
		return(buf.ro);
	    }
	    case STREAM: {
		StreamBuffer ret;
		if(((ret = GLReference.get(buf.ro, StreamBuffer.class)) == null) || (ret.rbuf.env != this)) {
		    if(buf.ro != null)
			buf.ro.dispose();
		    buf.ro = new GLReference<>(ret = new StreamBuffer(this, buf.size()));
		    StreamBuffer.Fill data = (buf.init == null) ? null : (StreamBuffer.Fill)buf.init.fill(buf, this);
		    StreamBuffer jdret = ret;
		    GLBuffer rbuf = ret.rbuf;
		    prepare((GLRender g) -> {
			    BGL gl = g.gl();
			    VboState.apply(gl, g.state, rbuf);
			    if(data == null) {
				gl.glBufferData(GL.GL_ARRAY_BUFFER, buf.size(), null, GL.GL_DYNAMIC_DRAW);
			    } else {
				ByteBuffer xfbuf = data.get();
				gl.glBufferData(GL.GL_ARRAY_BUFFER, buf.size(), xfbuf, GL.GL_DYNAMIC_DRAW);
				jdret.put(gl, xfbuf);
			    }
			    if(labels && (buf.desc != null))
				gl.glObjectLabel(GL.GL_BUFFER, rbuf, String.valueOf(buf.desc));
			    rbuf.setmem(MemStats.VERTICES, buf.size());
			});
		}
		return(ret);
	    }
	    case STATIC: {
		GLBuffer ret;
		if(((ret = GLReference.get(buf.ro, GLBuffer.class)) == null) || (ret.env != this)) {
		    if(buf.ro != null)
			buf.ro.dispose();
		    buf.ro = new GLReference<>(ret = new GLBuffer(this));
		    FillBuffers.Array data = (buf.init == null) ? null : (FillBuffers.Array)buf.init.fill(buf, this);
		    GLBuffer jdret = ret;
		    prepare((GLRender g) -> {
			    BGL gl = g.gl();
			    VboState.apply(gl, g.state, jdret);
			    gl.glBufferData(GL.GL_ARRAY_BUFFER, buf.size(), (data == null) ? null : data.data(), GL.GL_STATIC_DRAW);
			    if(labels && (buf.desc != null))
				gl.glObjectLabel(GL.GL_BUFFER, jdret, String.valueOf(buf.desc));
			    jdret.setmem(MemStats.VERTICES, buf.size());
			    if(data != null) data.dispose();
			});
		}
		return(ret);
	    }
	    default:
		throw(new Error());
	    }
	}
    }
    GLVertexArray prepare(Model mod, GLProgram prog) {
	synchronized(mod) {
	    GLVertexArray.ProgIndex idx;
	    if(((idx = GLReference.get(mod.ro, GLVertexArray.ProgIndex.class)) == null) || (idx.env != this)) {
		if(mod.ro != null)
		    mod.ro.dispose();
		mod.ro = new GLReference<>(idx = new GLVertexArray.ProgIndex(this, mod));
	    }
	    return(idx.get(prog, mod));
	}
    }
    GLTexture.Tex2D prepare(Texture2D tex) {
	synchronized(tex) {
	    GLTexture.Tex2D ret;
	    if(((ret = GLReference.get(tex.ro, GLTexture.Tex2D.class)) == null) || (ret.env != this)) {
		if(tex.ro != null)
		    tex.ro.dispose();
		tex.ro = new GLReference<>(ret = GLTexture.Tex2D.create(this, tex));
	    }
	    return(ret);
	}
    }
    GLTexture.Tex2D prepare(Texture2D.Sampler2D smp) {
	Texture2D tex = smp.tex;
	synchronized(tex) {
	    GLTexture.Tex2D ret = prepare(tex);
	    ret.setsampler(smp);
	    return(ret);
	}
    }
    GLTexture.Tex3D prepare(Texture3D tex) {
	synchronized(tex) {
	    GLTexture.Tex3D ret;
	    if(((ret = GLReference.get(tex.ro, GLTexture.Tex3D.class)) == null) || (ret.env != this)) {
		if(tex.ro != null)
		    tex.ro.dispose();
		tex.ro = new GLReference<>(ret = GLTexture.Tex3D.create(this, tex));
	    }
	    return(ret);
	}
    }
    GLTexture.Tex3D prepare(Texture3D.Sampler3D smp) {
	Texture3D tex = smp.tex;
	synchronized(tex) {
	    GLTexture.Tex3D ret = prepare(tex);
	    ret.setsampler(smp);
	    return(ret);
	}
    }
    GLTexture.Tex2DArray prepare(Texture2DArray tex) {
	synchronized(tex) {
	    GLTexture.Tex2DArray ret;
	    if(((ret = GLReference.get(tex.ro, GLTexture.Tex2DArray.class)) == null) || (ret.env != this)) {
		if(tex.ro != null)
		    tex.ro.dispose();
		tex.ro = new GLReference<>(ret = GLTexture.Tex2DArray.create(this, tex));
	    }
	    return(ret);
	}
    }
    GLTexture.Tex2DArray prepare(Texture2DArray.Sampler2DArray smp) {
	Texture2DArray tex = smp.tex;
	synchronized(tex) {
	    GLTexture.Tex2DArray ret = prepare(tex);
	    ret.setsampler(smp);
	    return(ret);
	}
    }
    GLTexture.Tex2DMS prepare(Texture2DMS tex) {
	synchronized(tex) {
	    GLTexture.Tex2DMS ret;
	    if(((ret = GLReference.get(tex.ro, GLTexture.Tex2DMS.class)) == null) || (ret.env != this)) {
		if(tex.ro != null)
		    tex.ro.dispose();
		tex.ro = new GLReference<>(ret = GLTexture.Tex2DMS.create(this, tex));
	    }
	    return(ret);
	}
    }
    GLTexture.Tex2DMS prepare(Texture2DMS.Sampler2DMS smp) {
	Texture2DMS tex = smp.tex;
	synchronized(tex) {
	    GLTexture.Tex2DMS ret = prepare(tex);
	    ret.setsampler(smp);
	    return(ret);
	}
    }
    GLTexture.TexCube prepare(TextureCube tex) {
	synchronized(tex) {
	    GLTexture.TexCube ret;
	    if(((ret = GLReference.get(tex.ro, GLTexture.TexCube.class)) == null) || (ret.env != this)) {
		if(tex.ro != null)
		    tex.ro.dispose();
		tex.ro = new GLReference<>(ret = GLTexture.TexCube.create(this, tex));
	    }
	    return(ret);
	}
    }
    GLTexture.TexCube prepare(TextureCube.SamplerCube smp) {
	TextureCube tex = smp.tex;
	synchronized(tex) {
	    GLTexture.TexCube ret = prepare(tex);
	    ret.setsampler(smp);
	    return(ret);
	}
    }

    Object prepuval(Object val) {
	if(val instanceof Texture.Sampler) {
	    if(val instanceof Texture2D.Sampler2D)
		return(prepare((Texture2D.Sampler2D)val));
	    if(val instanceof Texture3D.Sampler3D)
		return(prepare((Texture3D.Sampler3D)val));
	    if(val instanceof Texture2DArray.Sampler2DArray)
		return(prepare((Texture2DArray.Sampler2DArray)val));
	    if(val instanceof Texture2DMS.Sampler2DMS)
		return(prepare((Texture2DMS.Sampler2DMS)val));
	    if(val instanceof TextureCube.SamplerCube)
		return(prepare((TextureCube.SamplerCube)val));
	}
	return(val);
    }

    Object prepfval(Object val) {
	if(val instanceof Texture.Image)
	    return(GLFrameBuffer.prepimg(this, (Texture.Image)val));
	return(val);
    }

    public class TempData<T> implements Supplier<T> {
	private final Supplier<T> bk;
	private T d = null;

	public TempData(Supplier<T> bk) {this.bk = bk;}

	public T get() {
	    if(d == null) {
		synchronized(this) {
		    if(d == null)
			d = bk.get();
		}
	    }
	    return(d);
	}
    }

    public final Supplier<GLVertexArray> tempvao = new TempData<>(() -> new GLVertexArray(this));
    public final Supplier<GLBuffer> tempvertex = new TempData<>(() -> new GLBuffer(this));
    public final Supplier<GLBuffer> tempindex = new TempData<>(() -> new GLBuffer(this));

    static class SavedProg {
	final int hash;
	final ShaderMacro[] shaders;
	final GLProgram prog;
	SavedProg next;
	double lastused;

	SavedProg(int hash, ShaderMacro[] shaders, GLProgram prog) {
	    this.hash = hash;
	    this.shaders = Arrays.copyOf(shaders, shaders.length);
	    this.prog = prog;
	    this.lastused = Utils.rtime();
	}
    }

    private final Object pmon = new Object();
    private SavedProg[] ptab = new SavedProg[32];
    private int nprog = 0;

    /* Programs to keep before eviction considers running at all.
     *
     * This used to be a generational sweep: anything not drawn with in the
     * last sixty seconds was disposed, unconditionally. Programs are rebuilt
     * on demand, and glCompileShader/glLinkProgram block the render thread,
     * so a sweep that dropped a few hundred at once bought a stall for every
     * one of them that came back into view shortly after - which is most of
     * them, since sixty seconds of not drawing a shader says very little
     * about whether it is about to be drawn again. Measured against a session
     * that hitched to ~10fps at intervals: 526 table events, 365 evictions,
     * four sweeps dropping 217, 36, 36 and 54 programs.
     *
     * The working set observed in play peaks under 400, so the cache is left
     * alone below the cap and only trims down to it when genuinely over,
     * oldest-used first. A program is a small GPU object next to the several
     * hundred megabytes of texture the same session holds, so keeping the
     * ceiling well clear of the working set is the cheap side of this trade.
     * The cap is the safety valve against unbounded growth, not a target. */
    private static final int PROG_KEEP = 512;

    /* Opened on first use rather than in the constructor: the caps are what
     * say whether the driver has the extension at all, and a null cache is the
     * ordinary "link from source" path, so there is nothing to arrange in
     * advance. Null also covers the cache being switched off or unusable. */
    private ProgramCache progcache = null;
    private boolean progcacheinit = false;

    ProgramCache progcache() {
	synchronized(pmon) {
	    if(!progcacheinit) {
		progcacheinit = true;
		boolean supported = ((caps.major > 4) || ((caps.major == 4) && (caps.minor >= 1)) ||
				     caps.exts.contains("GL_ARB_get_program_binary"));
		progcache = ProgramCache.open(caps.vendor, caps.renderer, caps.version, supported);
	    }
	    return(progcache);
	}
    }

    /* The ladder of fallbacks this used to be existed because it reached into a widget that
     * might not be built yet. It now reads one volatile field, so there is nothing left to
     * fail and nothing to guard against. */
    static boolean shaderDbgEnabled() {
	return(haven.automated.nbots.core.NLog.diag());
    }

    private static java.io.BufferedWriter shaderLogWriter = null;
    private static final java.time.format.DateTimeFormatter shaderLogTime =
	java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /* Timestamped, because the only use for this log is lining its events up
     * against the frame times in plgob.log and the counters in vmem.log. An
     * untimed line cannot answer "did the eviction happen during the stall",
     * which is the entire question. */
    static void shaderLog(String line) {
	try {
	    java.io.BufferedWriter w;
	    synchronized(GLEnvironment.class) {
		if(shaderLogWriter == null) {
		    java.nio.file.Path p = java.nio.file.Paths.get("logs", "shader.log");
		    java.nio.file.Files.createDirectories(p.getParent());
		    shaderLogWriter = java.nio.file.Files.newBufferedWriter(p, java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		}
		w = shaderLogWriter;
	    }
	    String stamp = java.time.LocalDateTime.now().format(shaderLogTime);
	    synchronized(w) {
		w.write(stamp);
		w.write(' ');
		w.write(line);
		w.newLine();
		w.flush();
	    }
	} catch(Exception e) {
	}
    }


    private SavedProg findprog(int hash, ShaderMacro[] shaders) {
	int idx = hash & (ptab.length - 1);
	outer: for(SavedProg s = ptab[idx]; s != null; s = s.next) {
	    if(s.hash != hash)
		continue;
	    ShaderMacro[] a, b;
	    if(shaders.length < s.shaders.length) {
		a = shaders; b = s.shaders;
	    } else {
		a = s.shaders; b = shaders;
	    }
	    int i = 0;
	    for(; i < a.length; i++) {
		if(a[i] != b[i])
		    continue outer;
	    }
	    for(; i < b.length; i++) {
		if(b[i] != null)
		    continue outer;
	    }
	    return(s);
	}
	return(null);
    }

    private void rehash(int nlen) {
	SavedProg[] ntab = new SavedProg[nlen];
	for(int i = 0; i < ptab.length; i++) {
	    while(ptab[i] != null) {
		SavedProg s = ptab[i];
		ptab[i] = s.next;
		int ni = s.hash & (nlen - 1);
		s.next = ntab[ni];
		ntab[ni] = s;
	    }
	}
	ptab = ntab;
    }

    private void putprog(int hash, ShaderMacro[] shaders, GLProgram prog) {
	int idx = hash & (ptab.length - 1);
	SavedProg save = new SavedProg(hash, shaders, prog);
	save.next = ptab[idx];
	ptab[idx] = save;
	nprog++;
	if(shaderDbgEnabled()) shaderLog("SHADERDBG putprog hash=" + hash + " nprog=" + nprog + " prog=" + System.identityHashCode(prog));
	if(nprog > ptab.length)
	    rehash(ptab.length * 2);
    }

    public GLProgram getprog(int hash, ShaderMacro[] shaders) {
	synchronized(pmon) {
	    SavedProg s = findprog(hash, shaders);
	    if(s != null) {
		/* Not logged: this is the hot path, hit for every program on
		 * every frame, and writing a line per hit costs more than the
		 * eviction being investigated. */
		s.lastused = Utils.rtime();
		return(s.prog);
	    }
	}
	Collection<ShaderMacro> mods = new LinkedList<>();
	for(int i = 0; i < shaders.length; i++) {
	    if(shaders[i] != null)
		mods.add(shaders[i]);
	}
	GLProgram prog = GLProgram.build(this, mods);
	synchronized(pmon) {
	    SavedProg s = findprog(hash, shaders);
	    if(s != null) {
		prog.dispose();
		s.lastused = Utils.rtime();
		if(shaderDbgEnabled())
		    shaderLog("SHADERDBG getprog race-hit hash=" + hash + " prog=" + System.identityHashCode(s.prog));
		return(s.prog);
	    }
	    putprog(hash, shaders, prog);
	    return(prog);
	}
    }

    private void cleanprogs() {
	synchronized(pmon) {
	    if(nprog <= PROG_KEEP)
		return;
	    /* Over the cap: trim back to it, least-recently-drawn first.
	     * Locked programs are in use by the renderer right now and are
	     * never candidates, whatever their age. */
	    List<SavedProg> cand = new ArrayList<>();
	    for(int i = 0; i < ptab.length; i++) {
		for(SavedProg c = ptab[i]; c != null; c = c.next) {
		    if(c.prog.locked.get() < 1)
			cand.add(c);
		}
	    }
	    int excess = nprog - PROG_KEEP;
	    if((excess <= 0) || cand.isEmpty())
		return;
	    cand.sort((a, b) -> Double.compare(a.lastused, b.lastused));
	    int drop = Math.min(excess, cand.size());
	    Set<SavedProg> doomed = Collections.newSetFromMap(new IdentityHashMap<SavedProg, Boolean>());
	    for(int i = 0; i < drop; i++)
		doomed.add(cand.get(i));
	    if(shaderDbgEnabled())
		shaderLog("SHADERDBG cleanprogs enter nprog=" + nprog + " size=" + ptab.length + " keep=" + PROG_KEEP + " dropping=" + drop);
	    for(int i = 0; i < ptab.length; i++) {
		SavedProg c, p;
		for(c = ptab[i], p = null; c != null; c = c.next) {
		    /* Re-checked rather than trusted from the pass above: a
		     * program can be taken into use between the two. */
		    if(doomed.contains(c) && (c.prog.locked.get() < 1)) {
			if(p == null)
			    ptab[i] = c.next;
			else
			    p.next = c.next;
			if(shaderDbgEnabled())
			    shaderLog("SHADERDBG cleanprogs evict hash=" + c.hash + " prog=" + System.identityHashCode(c.prog) + " idle=" + String.format("%.1f", Utils.rtime() - c.lastused) + "s");
			c.prog.dispose();
			nprog--;
		    } else {
			p = c;
		    }
		}
	    }
	    /* XXX: Rehash into smaller table? It's probably not a
	     * problem, but it might be nice just for
	     * completeness. */
	    if(shaderDbgEnabled())
		shaderLog("SHADERDBG cleanprogs exit nprog=" + nprog);
	}
    }

    public Object progdump() {
	HashMap<String, Object> ret = new HashMap<>();
	synchronized(pmon) {
	    int seq = 0;
	    for(int i = 0; i < ptab.length; i++) {
		for(SavedProg p = ptab[i]; p != null; p = p.next) {
		    ret.put(String.format("p%d-idx", seq), i);
		    ret.put(String.format("p%d-hash", seq), p.hash);
		    ret.put(String.format("p%d-rc", seq), p.prog.locked.get());
		    ret.put(String.format("p%d-id", seq), System.identityHashCode(p.prog));
		    List<String> macros = new ArrayList<>();
		    List<Integer> macroi = new ArrayList<>();
		    for(int o = 0; o < p.shaders.length; o++) {
			macros.add(String.valueOf(p.shaders[o]));
			macroi.add(System.identityHashCode(p.shaders[o]));
		    }
		    ret.put(String.format("p%d-mac", seq), macros);
		    ret.put(String.format("p%d-macid", seq), macroi);
		    seq++;
		}
	    }
	}
	return(ret);
    }

    public boolean compatible(DrawList ob) {
	return((ob instanceof GLDrawList) && (((GLDrawList)ob).env == this));
    }

    public boolean compatible(Texture ob) {
	GLObject ro = GLReference.get(ob.ro, GLObject.class);
	return((ro != null) && (ro.env == this));
    }

    public boolean compatible(DataBuffer ob) {
	if(ob instanceof Model.Indices) {
	    Disposable ro = GLReference.get(((Model.Indices)ob).ro, Disposable.class);
	    if(ro instanceof StreamBuffer) ro = ((StreamBuffer)ro).rbuf;
	    return((ro != null) && (ro instanceof GLObject) && (((GLObject)ro).env == this));
	} else if(ob instanceof VertexArray.Buffer) {
	    Disposable ro = GLReference.get(((VertexArray.Buffer)ob).ro, Disposable.class);
	    if(ro instanceof StreamBuffer) ro = ((StreamBuffer)ro).rbuf;
	    return((ro != null) && (ro instanceof GLObject) && (((GLObject)ro).env == this));
	} else {
	    throw(new NotImplemented());
	}
    }

    private double lastpclean = Utils.rtime();
    public void clean() {
	double now = Utils.rtime();
	if(now - lastpclean > 60) {
	    cleanprogs();
	    lastpclean = now;
	}
    }

    private final Object seqmon = new Object();
    private boolean[] sequse = new boolean[16];
    private int seqhead = 1, seqtail = seqhead;

    private void seqresize(int nsz) {
	boolean[] cseq = sequse, nseq = new boolean[nsz];
	int csz = cseq.length;
	for(int i = 0; i < csz; i++)
	    nseq[(seqtail + i) & (nsz - 1)] = cseq[(seqtail + i) & (csz - 1)];
	sequse = nseq;
	if(nsz >= 0x4000)
	    Warning.warn("warning: dispose queue size increased to " + nsz);
    }

    int seqreg() {
	synchronized(seqmon) {
	    int seq = seqhead;
	    if(++seqhead == 0)
		seqhead = 1;
	    if(seqhead - seqtail == sequse.length - 1)
		seqresize(sequse.length << 1);
	    sequse[seq & (sequse.length - 1)] = true;
	    return(seq);
	}
    }

    void sequnreg(int seq) {
	if(seq == 0)
	    return;
	synchronized(seqmon) {
	    int m = sequse.length - 1;
	    int si = seq & m;
	    if(!sequse[si])
		throw(new AssertionError());
	    sequse[si] = false;
	    if(seq == seqtail) {
		while((seqhead - seqtail > 0) && !sequse[seqtail & m])
		    seqtail++;
	    }
	}
    }

    int dispseq() {
	synchronized(seqmon) {
	    return(seqhead);
	}
    }

    class Sequence implements Disposable {
	public final int no;
	private final Runnable clean;
	private final String desc;
	private volatile boolean cleaned = false;

	Sequence(Object owner) {
	    this.desc = owner.toString();
	    this.no = seqreg();
	    clean = Finalizer.finalize(owner, this::disposed);
	}

	private void disposed() {
	    sequnreg(no);
	    if(!cleaned) {
		Warning.warn("warning: disposal sequence leaked: " + desc);
	    }
	}

	public void dispose() {
	    cleaned = true;
	    clean.run();
	}
    }

    public int numprogs() {return(nprog);}
    public Caps caps() {return(caps);}

    public String memstats() {
	StringBuilder buf = new StringBuilder();
	MemStats[] sta = MemStats.values();
	for(int i = 0; i < sta.length; i++) {
	    if(i > 0)
		buf.append(" / ");
	    buf.append(String.format("%c %,d (%,d)", sta[i].name().charAt(0), stats_mem[i], stats_obj[i]));
	}
	return(buf.toString());
    }

    /** Copy of the per-pool GPU byte counts (INDICES, VERTICES, TEXTURES, VAOS, FBOS). */
    public long[] memBytes() {return(stats_mem.clone());}

    /** Copy of the per-pool GPU object counts (INDICES, VERTICES, TEXTURES, VAOS, FBOS). */
    public int[] memObjects() {return(stats_obj.clone());}

    public void dispose() {
	{
	    Collection<GLRender> copy;
	    synchronized(submitted) {
		copy = new ArrayList<>(submitted);
		submitted.clear();
		invalid = true;
	    }
	    for(GLRender cmd : copy) {
		cmd.gl.abort();
		cmd.dispose();
	    }
	}
	{
	    Collection<GLQuery> copy;
	    synchronized(drawmon) {
		copy = new ArrayList<>(queries);
		queries.clear();
	    }
	    for(GLQuery query : copy)
		query.abort();
	}
    }
}
