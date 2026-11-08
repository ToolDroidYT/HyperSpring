# Valida los shaders de TelonRenderer con Mesa y dibuja unos fotogramas de prueba.
import ctypes as C, re, numpy as np, sys
from PIL import Image
egl=C.CDLL('libEGL.so.1'); gl=C.CDLL('libGLESv2.so.2')
egl.eglGetProcAddress.restype=C.c_void_p
gpd=C.CFUNCTYPE(C.c_void_p,C.c_uint,C.c_void_p,C.c_void_p)(egl.eglGetProcAddress(b'eglGetPlatformDisplayEXT'))
egl.eglInitialize.argtypes=[C.c_void_p,C.c_void_p,C.c_void_p]
dpy=gpd(0x31DD,None,None); assert egl.eglInitialize(C.c_void_p(dpy),None,None)
egl.eglBindAPI(0x30A0)
egl.eglCreateContext.restype=C.c_void_p; egl.eglCreateContext.argtypes=[C.c_void_p,C.c_void_p,C.c_void_p,C.c_void_p]
ctx=egl.eglCreateContext(C.c_void_p(dpy),None,None,(C.c_int*3)(0x3098,2,0x3038))
egl.eglMakeCurrent.argtypes=[C.c_void_p]*4; assert egl.eglMakeCurrent(C.c_void_p(dpy),None,None,C.c_void_p(ctx))
src=open(sys.argv[1]).read()
def jstr(name):
    m=re.search(r'static final String '+name+r' =\s*(.*?);\n',src,re.S)
    return ''.join(bytes(l,'utf8').decode('unicode_escape') for l in re.findall(r'"((?:[^"\\]|\\.)*)"',m.group(1)))
V,F=jstr('VERTEX'),jstr('FRAGMENT')
gl.glCreateShader.restype=C.c_uint
def comp(t,s):
    sh=gl.glCreateShader(t); b=C.c_char_p(s.encode()); gl.glShaderSource(sh,1,C.byref(b),None); gl.glCompileShader(sh)
    ok=C.c_int(); gl.glGetShaderiv(sh,0x8B81,C.byref(ok)); log=C.create_string_buffer(4096); gl.glGetShaderInfoLog(sh,4096,None,log)
    assert ok.value, log.value.decode(); return sh
def prog(v,f):
    p=gl.glCreateProgram(); gl.glAttachShader(p,comp(0x8B31,v)); gl.glAttachShader(p,comp(0x8B30,f)); gl.glLinkProgram(p)
    ok=C.c_int(); gl.glGetProgramiv(p,0x8B82,C.byref(ok)); assert ok.value,'link'; return p
prog(V,F); print('telón: shaders OK (original)')
if len(sys.argv)<3: sys.exit(0)
# Para dibujar con Mesa: misma lógica con textura 2D normal.
F2=F.replace('#extension GL_OES_EGL_image_external : require\n','').replace('samplerExternalOES','sampler2D')
p=prog(V,F2); gl.glUseProgram(p); U=lambda n: gl.glGetUniformLocation(p,n.encode())
W,H,S=720,1640,0.5; bw,bh=int(W*S),int(H*S)
app=Image.open(sys.argv[2]).convert('RGBA').resize((W//2,H//2))
t=C.c_uint(); gl.glGenTextures(1,C.byref(t)); gl.glBindTexture(0x0DE1,t)
for k,v in ((0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)): gl.glTexParameteri(0x0DE1,k,v)
a=np.ascontiguousarray(np.asarray(app)); gl.glTexImage2D(0x0DE1,0,0x1908,app.width,app.height,0,0x1908,0x1401,a.ctypes.data_as(C.c_void_p))
o=C.c_uint(); gl.glGenTextures(1,C.byref(o)); gl.glBindTexture(0x0DE1,o); gl.glTexImage2D(0x0DE1,0,0x1908,bw,bh,0,0x1908,0x1401,None)
fb=C.c_uint(); gl.glGenFramebuffers(1,C.byref(fb)); gl.glBindFramebuffer(0x8D40,fb); gl.glFramebufferTexture2D(0x8D40,0x8CE0,0x0DE1,o,0)
gl.glBindTexture(0x0DE1,t)
for n in ('glUniform1f',): getattr(gl,n).argtypes=[C.c_int,C.c_float]
gl.glUniform2f.argtypes=[C.c_int,C.c_float,C.c_float]
ROWS=int(re.search(r'ROWS = (\d+)',src).group(1)); POWER=float(sys.argv[4]) if len(sys.argv)>4 else 1.0
mv=[]
for i in range(ROWS+1): mv+=[i/ROWS,0.0,i/ROWS,1.0]
q=(C.c_float*len(mv))(*mv); loc=gl.glGetAttribLocation(p,b'aRow'); gl.glEnableVertexAttribArray(loc); gl.glVertexAttribPointer(loc,2,0x1406,0,0,q)
ident=(C.c_float*16)(1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1)
tiles=[]
for tx,bend,bl in ((560,60,30),(360,180,43),(160,150,32),(40,40,8),(0,0,0)):
    gl.glViewport(0,0,bw,bh); gl.glClearColor.argtypes=[C.c_float]*4; gl.glClearColor(0,0,0,0); gl.glClear(0x4000)
    M=(C.c_float*9)(1,0,0, 0,1,0, tx,0,1)
    gl.glUniformMatrix3fv(U('M'),1,0,M); gl.glUniform2f(U('win'),W,H); gl.glUniform1f(U('bend'),bend)
    gl.glUniform1f(U('power'),POWER); gl.glUniform1f(U('margin'),2.0); gl.glUniform1f(U('scale'),S); gl.glUniform2f(U('buf'),bw,bh)
    gl.glUniformMatrix4fv(U('texM'),1,0,ident); gl.glUniform1f(U('radius'),40.0); gl.glUniform1f(U('alpha'),1.0); gl.glUniform1f(U('aa'),S)
    gl.glUniform1f(U('blur'),float(bl)); gl.glUniform1i(U('content'),0); gl.glDrawArrays(5,0,(ROWS+1)*2)
    out=np.zeros((bh,bw,4),np.uint8); gl.glReadPixels(0,0,bw,bh,0x1908,0x1401,out.ctypes.data_as(C.c_void_p))
    L=out[::-1].astype(float)/255; bg=np.full((bh,bw,3),(70,70,80),float)/255
    tiles.append(Image.fromarray(((L[...,:3]+bg*(1-L[...,3:4]))*255).astype(np.uint8)))
print('error GL:',gl.glGetError())
S2=Image.new('RGB',(bw*len(tiles)+8*len(tiles),bh),'white')
for i,im in enumerate(tiles): S2.paste(im,(i*(bw+8),0))
S2.save(sys.argv[3]); print('ok', sys.argv[3])
