import ctypes as C, re, numpy as np, sys
from PIL import Image
egl=C.CDLL('libEGL.so.1'); gl=C.CDLL('libGLESv2.so.2')
egl.eglGetProcAddress.restype=C.c_void_p
gpd=C.CFUNCTYPE(C.c_void_p,C.c_uint,C.c_void_p,C.c_void_p)(egl.eglGetProcAddress(b'eglGetPlatformDisplayEXT'))
egl.eglInitialize.argtypes=[C.c_void_p,C.c_void_p,C.c_void_p]
dpy=gpd(0x31DD,None,None)  # EGL_PLATFORM_SURFACELESS_MESA
assert egl.eglInitialize(C.c_void_p(dpy),None,None), 'init'
egl.eglBindAPI(0x30A0)  # GLES
cfg=C.c_void_p(None)  # EGL_KHR_no_config_context
egl.eglCreateContext.restype=C.c_void_p; egl.eglCreateContext.argtypes=[C.c_void_p,C.c_void_p,C.c_void_p,C.c_void_p]
ctx=egl.eglCreateContext(C.c_void_p(dpy),cfg,None,(C.c_int*3)(0x3098,2,0x3038))
egl.eglMakeCurrent.argtypes=[C.c_void_p]*4
assert egl.eglMakeCurrent(C.c_void_p(dpy),None,None,C.c_void_p(ctx)),'current'
gl.glGetString.restype=C.c_char_p
print('GL:',gl.glGetString(0x1F02).decode(),'|',gl.glGetString(0x1F01).decode())
ext=gl.glGetString(0x1F03).decode(); print('external OES:', 'GL_OES_EGL_image_external' in ext)
src=open('/home/claude/IceHome/app/src/main/java/com/icehome/up/GlRenderer.java').read()
def jstr(name):
    m=re.search(r'static final String '+name+r' =\s*(.*?);\n',src,re.S)
    body=m.group(1); out=''
    for lit,ident in re.findall(r'"((?:[^"\\]|\\.)*)"|\b([A-Z_]{4,})\b',re.sub(r'//[^\n]*','',body)):
        out+= bytes(lit,'utf8').decode('unicode_escape') if ident=='' else JS[ident]
    return out
import re, numpy as np
from PIL import Image, ImageFilter
src=open('/home/claude/IceHome/app/src/main/java/com/icehome/up/GlRenderer.java').read()
def jstr(name, JS):
    m=re.search(r'static final String '+name+r' =\s*(.*?);\n',src,re.S); out=''
    for lit,ident in re.findall(r'"((?:[^"\\]|\\.)*)"|\b([A-Z_]{4,})\b',re.sub(r'//[^\n]*','',m.group(1))):
        out+= bytes(lit,'utf8').decode('unicode_escape') if ident=='' else JS[ident]
    return out
JS={}
for n in ('SPLASH_AT','VERTEX','FRAGMENT_BODY','FRAGMENT_WINDOW','FRAGMENT_SPLASH'): JS[n]=jstr(n,JS)
def comp(t,s):
    sh=gl.glCreateShader(t); b=C.c_char_p(s.encode()); gl.glShaderSource(sh,1,C.byref(b),None); gl.glCompileShader(sh)
    ok=C.c_int(); gl.glGetShaderiv(sh,0x8B81,C.byref(ok)); log=C.create_string_buffer(4096); gl.glGetShaderInfoLog(sh,4096,None,log)
    assert ok.value, log.value.decode(); return sh
P={}
for w in (True,False):
    p=gl.glCreateProgram(); gl.glAttachShader(p,comp(0x8B31,JS['VERTEX'])); gl.glAttachShader(p,comp(0x8B30,(JS['FRAGMENT_WINDOW'] if w else JS['FRAGMENT_SPLASH'])+JS['FRAGMENT_BODY'])); gl.glLinkProgram(p)
    ok=C.c_int(); gl.glGetProgramiv(p,0x8B82,C.byref(ok)); print('ventana' if w else 'inicio','OK' if ok.value else 'ERROR'); P[w]=p
# un fotograma con el programa de inicio
gl.glUniform1f.argtypes=[C.c_int,C.c_float]; gl.glUniform4f.argtypes=[C.c_int]+[C.c_float]*4; gl.glClearColor.argtypes=[C.c_float]*4
crop=(52,34,464,904); app=Image.open('/home/claude/vid/F/100.png').crop(crop).convert('RGBA'); W,H=app.size; bw,bh=W//2,H//2
def tex(img):
    t=C.c_uint(); gl.glGenTextures(1,C.byref(t)); gl.glBindTexture(0x0DE1,t)
    for k,v in ((0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)): gl.glTexParameteri(0x0DE1,k,v)
    a=np.ascontiguousarray(np.asarray(img)); gl.glTexImage2D(0x0DE1,0,0x1908,img.width,img.height,0,0x1908,0x1401,a.ctypes.data_as(C.c_void_p)); return t
o=C.c_uint(); gl.glGenTextures(1,C.byref(o)); gl.glBindTexture(0x0DE1,o); gl.glTexImage2D(0x0DE1,0,0x1908,bw,bh,0,0x1908,0x1401,None)
fb=C.c_uint(); gl.glGenFramebuffers(1,C.byref(fb)); gl.glBindFramebuffer(0x8D40,fb); gl.glFramebufferTexture2D(0x8D40,0x8CE0,0x0DE1,o,0)
gl.glActiveTexture(0x84C0); tex(app); gl.glActiveTexture(0x84C1); tex(Image.open('/home/claude/vid/F/100.png').crop((200,470,264,534)).convert('RGBA'))
gl.glViewport(0,0,bw,bh); gl.glClearColor(0,0,0,0); gl.glClear(0x4000); p=P[False]; gl.glUseProgram(p); U=lambda n: gl.glGetUniformLocation(p,n.encode())
import json,os; P_=json.loads(os.environ.get('GENIE','{}')); I=P_.get('icon',[214,484,248,518]); Cr=P_.get('cur',[107,120,330,700])
gl.glUniform1i(U('content'),0); gl.glUniform1i(U('iconTex'),1); gl.glUniform4f(U('cur'),*map(float,Cr)); gl.glUniform4f(U('icon'),*map(float,I))
gl.glUniform4f(U('g'),P_.get('pw',0.55),1/(1+0.35*2.2),1+0.6*2.2,22.0); gl.glUniform1f(U('iconMix'),0.0); gl.glUniform1f(U('fullHalf'),W/2)
gl.glUniform4f(U('bg'),1,1,1,1); gl.glUniform1f(U('scale'),0.5); gl.glUniform1f(U('bufH'),float(bh))
gl.glUniform3f.argtypes=[C.c_int]+[C.c_float]*3; gl.glUniform3f(U('splash'),0.15,0.15*W/H,1.0); gl.glUniform1f(U('bend'),float(sys.argv[1]) if len(sys.argv)>1 else 0.0); gl.glUniform1f(U('flip'),float(P_.get('flip',0.0)))
ROWS=int(re.search(r'ROWS = (\d+)',src).group(1)); mv=[]
for i in range(ROWS+1): mv+= [i/ROWS,0.0,i/ROWS,1.0]
q=(C.c_float*len(mv))(*mv); loc=gl.glGetAttribLocation(p,b'aRow'); gl.glEnableVertexAttribArray(loc); gl.glVertexAttribPointer(loc,2,0x1406,0,0,q)
gl.glUniform2f.argtypes=[C.c_int,C.c_float,C.c_float]; gl.glUniform2f(U('buf'),float(bw),float(bh)); gl.glDrawArrays(5,0,(ROWS+1)*2)
out=np.zeros((bh,bw,4),np.uint8); gl.glReadPixels(0,0,bw,bh,0x1908,0x1401,out.ctypes.data_as(C.c_void_p)); print('error GL:',gl.glGetError())
bgimg=Image.new('RGB',(bw,bh),(40,120,60)); L=out[::-1].astype(float)/255
Image.fromarray(((L[...,:3]+np.asarray(bgimg)/255*(1-L[...,3:4]))*255).astype(np.uint8)).save('single.png')
