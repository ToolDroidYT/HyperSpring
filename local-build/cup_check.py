# Simula la forma de copa del genio (cierre hacia un icono) con los shaders reales de GlRenderer.
# Uso: python3 cup_check.py salida.png [intensidad]
import ctypes as C, re, numpy as np, sys, math
from PIL import Image, ImageDraw, ImageFilter
egl=C.CDLL('libEGL.so.1'); gl=C.CDLL('libGLESv2.so.2')
egl.eglGetProcAddress.restype=C.c_void_p
gpd=C.CFUNCTYPE(C.c_void_p,C.c_uint,C.c_void_p,C.c_void_p)(egl.eglGetProcAddress(b'eglGetPlatformDisplayEXT'))
egl.eglInitialize.argtypes=[C.c_void_p]*3
dpy=gpd(0x31DD,None,None); assert egl.eglInitialize(C.c_void_p(dpy),None,None); egl.eglBindAPI(0x30A0)
egl.eglCreateContext.restype=C.c_void_p; egl.eglCreateContext.argtypes=[C.c_void_p]*4
ctx=egl.eglCreateContext(C.c_void_p(dpy),None,None,(C.c_int*3)(0x3098,2,0x3038))
egl.eglMakeCurrent.argtypes=[C.c_void_p]*4; assert egl.eglMakeCurrent(C.c_void_p(dpy),None,None,C.c_void_p(ctx))
src=open('/home/claude/IceHome/app/src/main/java/com/icehome/up/GlRenderer.java').read()
JS={}
def jstr(name):
    if name in JS: return JS[name]
    m=re.search(r'static final String '+name+r' =\s*(.*?);\n',src,re.S); out=''
    for lit,ident in re.findall(r'"((?:[^"\\]|\\.)*)"|\b([A-Z_]{4,})\b',re.sub(r'//[^\n]*','',m.group(1))):
        out+= bytes(lit,'utf8').decode('unicode_escape') if ident=='' else jstr(ident)
    JS[name]=out; return out
V=jstr('VERTEX'); F=jstr('FRAGMENT_WINDOW')+jstr('FRAGMENT_BODY')
F=F.replace('#extension GL_OES_EGL_image_external : require\n','').replace('samplerExternalOES','sampler2D')
gl.glCreateShader.restype=C.c_uint
def comp(t,s):
    sh=gl.glCreateShader(t); b=C.c_char_p(s.encode()); gl.glShaderSource(sh,1,C.byref(b),None); gl.glCompileShader(sh)
    ok=C.c_int(); gl.glGetShaderiv(sh,0x8B81,C.byref(ok)); log=C.create_string_buffer(4096); gl.glGetShaderInfoLog(sh,4096,None,log)
    assert ok.value, log.value.decode(); return sh
p=gl.glCreateProgram(); gl.glAttachShader(p,comp(0x8B31,V)); gl.glAttachShader(p,comp(0x8B30,F)); gl.glLinkProgram(p)
ok=C.c_int(); gl.glGetProgramiv(p,0x8B82,C.byref(ok)); assert ok.value; gl.glUseProgram(p)
U=lambda n: gl.glGetUniformLocation(p,n.encode())
for n in ('glUniform1f',): getattr(gl,n).argtypes=[C.c_int,C.c_float]
gl.glUniform2f.argtypes=[C.c_int]+[C.c_float]*2; gl.glUniform3f.argtypes=[C.c_int]+[C.c_float]*3; gl.glUniform4f.argtypes=[C.c_int]+[C.c_float]*4
W,H,S=720,1600,0.5; bw,bh=int(W*S),int(H*S)
app=Image.new('RGBA',(W//2,H//2),(246,232,240,255)); d=ImageDraw.Draw(app)
d.rectangle((12,60,W//2-12,260),fill=(70,120,60,255)); d.text((14,30),'Galeria',fill=(30,30,30,255))
for i in range(9): d.rounded_rectangle((14,290+i*52,W//2-14,330+i*52),8,fill=(255,255,255,255))
def tex(im):
    t=C.c_uint(); gl.glGenTextures(1,C.byref(t)); gl.glBindTexture(0x0DE1,t)
    for k,v in ((0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)): gl.glTexParameteri(0x0DE1,k,v)
    a=np.ascontiguousarray(np.flipud(np.asarray(im))); gl.glTexImage2D(0x0DE1,0,0x1908,im.width,im.height,0,0x1908,0x1401,a.ctypes.data_as(C.c_void_p)); return t
gl.glActiveTexture(0x84C0); tA=tex(app); gl.glActiveTexture(0x84C1); tex(Image.new('RGBA',(8,8),(230,60,80,255))); gl.glActiveTexture(0x84C0)
o=C.c_uint(); gl.glGenTextures(1,C.byref(o)); gl.glBindTexture(0x0DE1,o); gl.glTexImage2D(0x0DE1,0,0x1908,bw,bh,0,0x1908,0x1401,None)
fb=C.c_uint(); gl.glGenFramebuffers(1,C.byref(fb)); gl.glBindFramebuffer(0x8D40,fb); gl.glFramebufferTexture2D(0x8D40,0x8CE0,0x0DE1,o,0)
gl.glActiveTexture(0x84C0); gl.glBindTexture(0x0DE1,tA)
ROWS=int(re.search(r'ROWS = (\d+)',src).group(1)); mv=[]
for i in range(ROWS+1): mv+=[i/ROWS,0.0,i/ROWS,1.0]
mesh=(C.c_float*len(mv))(*mv); loc=gl.glGetAttribLocation(p,b'aRow'); gl.glEnableVertexAttribArray(loc); gl.glVertexAttribPointer(loc,2,0x1406,0,0,mesh)
ident=(C.c_float*16)(1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1)
g=float(sys.argv[2]) if len(sys.argv)>2 else 2.2
icon=(330,1080,420,1170); full=(0,0,W,H)
sm=lambda v: (lambda x: x*x*(3-2*x))(min(1,max(0,v)))
bgimg=Image.new('RGB',(bw,bh),(60,110,50)).filter(ImageFilter.GaussianBlur(4))
tiles=[]
icon=tuple(int(v) for v in (sys.argv[3].split(",") if len(sys.argv)>3 else (320,760,410,850)))
for f in (0.002,0.02,0.06,0.16,0.28,0.42,0.6):
    q=1-(1-f)**2.2                        # apertura del rectángulo de MIUI (aprox.)
    cur=[icon[i]+(full[i]-icon[i])*q for i in range(4)]
    pw=q; ph=q
    LM,GM=(0.8,0.75) if len(sys.argv)>4 and sys.argv[4]=="ice" else (1,1); cl=1+0.9*LM*g; cg=1+0.5*GM*g; vg=1+(cg-1)*0.12; env=sm(ph/0.12)
    far=(1-(1-ph)**cl-ph)*env; near=(ph**vg-ph)*env
    cur[1]+=(full[1]-icon[1])*far; cur[3]+=(full[3]-icon[3])*near
    e=1-q
    gl.glViewport(0,0,bw,bh); gl.glClearColor.argtypes=[C.c_float]*4; gl.glClearColor(0,0,0,0); gl.glClear(0x4000)
    gl.glEnable(0x0BE2); gl.glBlendFunc(1,0x0303)
    gl.glUniformMatrix4fv(U('texM'),1,0,ident); gl.glUniform1i(U('content'),0); gl.glUniform1i(U('iconTex'),1); gl.glUniform1f(U('fade'),1.0)
    gl.glUniform3f(U('splash'),0,0,0); gl.glUniform4f(U('cur'),*cur); gl.glUniform4f(U('icon'),*map(float,icon))
    gl.glUniform4f(U('g'),pw,cl,cg,48*(1-e)+22*e); gl.glUniform1f(U('iconMix'),0.0); gl.glUniform1f(U('fullHalf'),W/2)
    gl.glUniform4f(U('bg'),1,1,1,1); gl.glUniform1f(U('scale'),S); gl.glUniform1f(U('bufH'),float(bh)); gl.glUniform2f(U('buf'),float(bw),float(bh))
    gl.glUniform1f(U('bend'),0.0); gl.glUniform1f(U('flip'),0.0); gl.glUniform1f(U('cup'),1.0)
    gl.glDrawArrays(5,0,(ROWS+1)*2)
    out=np.zeros((bh,bw,4),np.uint8); gl.glReadPixels(0,0,bw,bh,0x1908,0x1401,out.ctypes.data_as(C.c_void_p))
    L=out[::-1].astype(float)/255; bg=np.asarray(bgimg).astype(float)/255
    if len(sys.argv)>4 and sys.argv[4]=='ice': bg=bg*(1-0.14*q)
    tiles.append(Image.fromarray(((L[...,:3]+bg*(1-L[...,3:4]))*255).astype(np.uint8)))
print('error GL:',gl.glGetError())
o2=Image.new('RGB',((bw+6)*len(tiles),bh),'white')
for i,im in enumerate(tiles): o2.paste(im,(i*(bw+6),0))
o2.save(sys.argv[1]); print('ok',sys.argv[1])
