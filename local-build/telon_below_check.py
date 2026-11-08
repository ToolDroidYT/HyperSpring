# Valida todos los shaders de TelonRenderer (Mesa) y simula la pantalla de debajo desenfocada
# con la de encima entrando: uso  python3 telon_below_check.py TelonRenderer.java salida.png
import ctypes as C, re, numpy as np, sys
from PIL import Image, ImageDraw
egl=C.CDLL('libEGL.so.1'); gl=C.CDLL('libGLESv2.so.2')
egl.eglGetProcAddress.restype=C.c_void_p
gpd=C.CFUNCTYPE(C.c_void_p,C.c_uint,C.c_void_p,C.c_void_p)(egl.eglGetProcAddress(b'eglGetPlatformDisplayEXT'))
egl.eglInitialize.argtypes=[C.c_void_p,C.c_void_p,C.c_void_p]
dpy=gpd(0x31DD,None,None); assert egl.eglInitialize(C.c_void_p(dpy),None,None)
egl.eglBindAPI(0x30A0)
egl.eglCreateContext.restype=C.c_void_p; egl.eglCreateContext.argtypes=[C.c_void_p]*4
ctx=egl.eglCreateContext(C.c_void_p(dpy),None,None,(C.c_int*3)(0x3098,2,0x3038))
egl.eglMakeCurrent.argtypes=[C.c_void_p]*4; assert egl.eglMakeCurrent(C.c_void_p(dpy),None,None,C.c_void_p(ctx))
src=open(sys.argv[1]).read()
def jstr(name):
    m=re.search(r'static final String '+name+r' =\s*(.*?);\n',src,re.S)
    body=m.group(1)
    s=''.join(bytes(l,'utf8').decode('unicode_escape') for l in re.findall(r'"((?:[^"\\]|\\.)*)"',body))
    if re.search(r'\+\s*TENT\s*$',body.strip()): s+=jstr('TENT')
    return s
gl.glCreateShader.restype=C.c_uint
def comp(t,s):
    sh=gl.glCreateShader(t); b=C.c_char_p(s.encode()); gl.glShaderSource(sh,1,C.byref(b),None); gl.glCompileShader(sh)
    ok=C.c_int(); gl.glGetShaderiv(sh,0x8B81,C.byref(ok)); log=C.create_string_buffer(4096); gl.glGetShaderInfoLog(sh,4096,None,log)
    assert ok.value, log.value.decode()+'\n'+s; return sh
def prog(v,f):
    p=gl.glCreateProgram(); gl.glAttachShader(p,comp(0x8B31,v)); gl.glAttachShader(p,comp(0x8B30,f)); gl.glLinkProgram(p)
    ok=C.c_int(); gl.glGetProgramiv(p,0x8B82,C.byref(ok)); assert ok.value,'link'; return p
V,VQ=jstr('VERTEX'),jstr('VERTEX_QUAD')
F,FB,FDE,FD=jstr('FRAGMENT'),jstr('FRAGMENT_BELOW'),jstr('FRAGMENT_DOWN_EXT'),jstr('FRAGMENT_DOWN')
for v,f,n in ((V,F,'encima'),(V,FB,'debajo'),(VQ,FDE,'1/4'),(VQ,FD,'1/8')): prog(v,f); print('shaders OK:',n)
ext=lambda s: s.replace('#extension GL_OES_EGL_image_external : require\n','').replace('samplerExternalOES','sampler2D')
pTop,pBel,pDE,pD=prog(V,ext(F)),prog(V,ext(FB)),prog(VQ,ext(FDE)),prog(VQ,FD)
gl.glUniform1f.argtypes=[C.c_int,C.c_float]; gl.glUniform2f.argtypes=[C.c_int,C.c_float,C.c_float]
gl.glClearColor.argtypes=[C.c_float]*4
def tex(w,h,data=None):
    t=C.c_uint(); gl.glGenTextures(1,C.byref(t)); gl.glBindTexture(0x0DE1,t)
    for k,v in ((0x2801,0x2601),(0x2800,0x2601),(0x2802,0x812F),(0x2803,0x812F)): gl.glTexParameteri(0x0DE1,k,v)
    gl.glTexImage2D(0x0DE1,0,0x1908,w,h,0,0x1908,0x1401,None if data is None else data.ctypes.data_as(C.c_void_p)); return t
def fbo(t):
    f=C.c_uint(); gl.glGenFramebuffers(1,C.byref(f)); gl.glBindFramebuffer(0x8D40,f); gl.glFramebufferTexture2D(0x8D40,0x8CE0,0x0DE1,t,0)
    assert gl.glCheckFramebufferStatus(0x8D40)==0x8CD5; return f
W,H,S=720,1640,0.5
def fake(color,title):
    im=Image.new('RGBA',(W//2,H//2),(245,245,247,255)); d=ImageDraw.Draw(im)
    d.rectangle((0,0,W//2,70),fill=color)
    d.text((16,30),title,fill='white')
    for i in range(14):
        y=100+i*50; d.rounded_rectangle((14,y,W//2-14,y+40),8,fill=(255,255,255,255),outline=(220,220,225,255))
        d.ellipse((22,y+6,50,y+34),fill=color); d.text((62,y+8),f'Elemento {i+1}  ·  texto de prueba',fill=(30,30,30,255))
    return im
up=lambda im: np.ascontiguousarray(np.flipud(np.asarray(im)))   # como lo deja texM en Android
below_img,top_img=fake((40,120,220,255),'Ajustes'),fake((220,80,60,255),'Wi-Fi')
cw,ch=below_img.size
tBelow,tTop=tex(cw,ch,up(below_img)),tex(cw,ch,up(top_img))
ident=(C.c_float*16)(1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1)
quad=(C.c_float*8)(0,0,1,0,0,1,1,1)
def drawq(p):
    loc=gl.glGetAttribLocation(p,b'aPos'); gl.glEnableVertexAttribArray(loc); gl.glVertexAttribPointer(loc,2,0x1406,0,0,quad); gl.glDrawArrays(5,0,4); gl.glDisableVertexAttribArray(loc)
U=lambda p,n: gl.glGetUniformLocation(p,n.encode())
# --- desenfoque una vez (como prepareBelow)
spread=float(sys.argv[3]) if len(sys.argv)>3 else 1.0
w1,h1=max(8,cw//4),max(8,ch//4); w2,h2=max(4,w1//2),max(4,h1//2)
t1,t2=tex(w1,h1),tex(w2,h2); f1,f2=fbo(t1),fbo(t2)
gl.glDisable(0x0BE2)
gl.glBindFramebuffer(0x8D40,f1); gl.glViewport(0,0,w1,h1); gl.glUseProgram(pDE)
gl.glUniformMatrix4fv(U(pDE,'texM'),1,0,ident); gl.glUniform2f(U(pDE,'step'),1.5*spread/cw,1.5*spread/ch)
gl.glActiveTexture(0x84C0); gl.glBindTexture(0x0DE1,tBelow); gl.glUniform1i(U(pDE,'src'),0); drawq(pDE)
gl.glBindFramebuffer(0x8D40,f2); gl.glViewport(0,0,w2,h2); gl.glUseProgram(pD)
gl.glUniform2f(U(pD,'step'),spread/w1,spread/h1); gl.glBindTexture(0x0DE1,t1); gl.glUniform1i(U(pD,'src'),0); drawq(pD)
# --- fotogramas
bw,bh=int(W*S),int(H*S); to=tex(bw,bh); fo=fbo(to)
ROWS=int(re.search(r'ROWS = (\d+)',src).group(1)); POWER=float(sys.argv[4]) if len(sys.argv)>4 else 1.0
mv=[]
for i in range(ROWS+1): mv+=[i/ROWS,0.0,i/ROWS,1.0]
mesh=(C.c_float*len(mv))(*mv)
def common(p,tx):
    loc=gl.glGetAttribLocation(p,b'aRow'); gl.glEnableVertexAttribArray(loc); gl.glVertexAttribPointer(loc,2,0x1406,0,0,mesh)
    gl.glUniformMatrix3fv(U(p,'M'),1,0,(C.c_float*9)(1,0,0, 0,1,0, tx,0,1)); gl.glUniform2f(U(p,'win'),W,H)
    gl.glUniform1f(U(p,'power'),POWER); gl.glUniform1f(U(p,'margin'),2.0); gl.glUniform1f(U(p,'scale'),S); gl.glUniform2f(U(p,'buf'),bw,bh)
    gl.glUniformMatrix4fv(U(p,'texM'),1,0,ident); gl.glUniform1f(U(p,'radius'),40.0); gl.glUniform1f(U(p,'alpha'),1.0); gl.glUniform1f(U(p,'aa'),S)
tiles=[]
# (avance, x de encima, doblez, estela, x de debajo, oscurecido, desenfoque)
for f in (0.0,0.1,0.2,0.35,0.6):
    e=1-(1-f)**3
    txT=W*(1-e); txB=-0.25*W*e; dim=0.5*e; amt=1-(1-e)**2
    bend=[30,170,120,40,0][int(f*4)]; bl=[10,40,30,10,0][int(f*4)]
    gl.glBindFramebuffer(0x8D40,fo); gl.glViewport(0,0,bw,bh); gl.glClearColor(0,0,0,0); gl.glClear(0x4000)
    gl.glEnable(0x0BE2); gl.glBlendFunc(1,0x0303)
    gl.glUseProgram(pBel); common(pBel,txB); gl.glUniform1f(U(pBel,'bend'),0.0)
    gl.glUniform2f(U(pBel,'btexel'),1/w2,1/h2); gl.glUniform1f(U(pBel,'amount'),amt); gl.glUniform1f(U(pBel,'dim'),dim)
    gl.glActiveTexture(0x84C0); gl.glBindTexture(0x0DE1,tBelow); gl.glUniform1i(U(pBel,'content'),0)
    gl.glActiveTexture(0x84C1); gl.glBindTexture(0x0DE1,t2); gl.glUniform1i(U(pBel,'blurred'),1); gl.glActiveTexture(0x84C2); gl.glBindTexture(0x0DE1,t1); gl.glUniform1i(U(pBel,'soft'),2); gl.glActiveTexture(0x84C0)
    gl.glDrawArrays(5,0,(ROWS+1)*2)
    gl.glUseProgram(pTop); common(pTop,txT); gl.glUniform1f(U(pTop,'bend'),float(bend)); gl.glUniform1f(U(pTop,'blur'),float(bl))
    gl.glBindTexture(0x0DE1,tTop); gl.glUniform1i(U(pTop,'content'),0); gl.glDrawArrays(5,0,(ROWS+1)*2)
    out=np.zeros((bh,bw,4),np.uint8); gl.glReadPixels(0,0,bw,bh,0x1908,0x1401,out.ctypes.data_as(C.c_void_p))
    L=out[::-1].astype(float)/255; bg=np.full((bh,bw,3),(0,0,0),float)/255
    tiles.append(Image.fromarray(((L[...,:3]+bg*(1-L[...,3:4]))*255).astype(np.uint8)))
print('error GL:',gl.glGetError())
S2=Image.new('RGB',(bw*len(tiles)+8*len(tiles),bh),'white')
for i,im in enumerate(tiles): S2.paste(im,(i*(bw+8),0))
S2.save(sys.argv[2]); print('ok',sys.argv[2])
