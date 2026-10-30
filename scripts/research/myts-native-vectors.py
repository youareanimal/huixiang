"""Run only in the isolated Actions research job with pinned public TS6 beta5.
Loads selected native routines without starting the client or any account session.
All inputs are synthetic and none can authenticate to TeamSpeak services.
"""
import ctypes, hashlib, json, mmap, struct, sys
from pathlib import Path

binary = Path(sys.argv[1]).read_bytes()
assert binary[:6] == b'\x7fELF\x02\x01'
shoff = struct.unpack_from('<Q', binary, 40)[0]
shsize, shcount, shnames = struct.unpack_from('<HHH', binary, 58)
sections = [struct.unpack_from('<IIQQQQIIQQ', binary, shoff + i * shsize) for i in range(shcount)]
name_section = sections[shnames]
names = binary[name_section[4]:name_section[4] + name_section[5]]
def section_name(s):
    return names[s[0]:names.index(b'\0', s[0])].decode()
sections_by_name = {section_name(s): s for s in sections}
phoff = struct.unpack_from('<Q', binary, 32)[0]
phsize, phcount = struct.unpack_from('<HH', binary, 54)
segments = [struct.unpack_from('<IIQQQQQQ', binary, phoff + i * phsize) for i in range(phcount)]
load_segments = [s for s in segments if s[0] == 1]
size = max(s[3] + s[6] for s in load_segments)
image = mmap.mmap(-1, size, prot=mmap.PROT_READ | mmap.PROT_WRITE | mmap.PROT_EXEC)
base = ctypes.addressof(ctypes.c_char.from_buffer(image))
for s in load_segments:
    image[s[3]:s[3] + s[5]] = binary[s[2]:s[2] + s[5]]
libraries = [ctypes.CDLL(None), ctypes.CDLL('libm.so.6'), ctypes.CDLL('libstdc++.so.6'), ctypes.CDLL('libgcc_s.so.1')]
dyn = sections_by_name['.dynsym']
strings = sections_by_name['.dynstr']
strtab = binary[strings[4]:strings[4] + strings[5]]
unresolved = set()
@ctypes.CFUNCTYPE(None)
def unsupported():
    import os
    os._exit(71)
unsupported_address = ctypes.cast(unsupported, ctypes.c_void_p).value

def symbol_address(index):
    name, info, other, shndx, value, length = struct.unpack_from('<IBBHQQ', binary, dyn[4] + index * dyn[9])
    name = strtab[name:strtab.index(b'\0', name)].decode()
    if shndx and name not in {'malloc', 'calloc', 'realloc', 'free', 'memcpy', 'memmove', 'memset'}:
        return base + value
    for lib in libraries:
        try:
            return ctypes.cast(getattr(lib, name), ctypes.c_void_p).value
        except AttributeError:
            pass
    unresolved.add(name)
    return unsupported_address

for section in sections:
    if section[1] != 4:
        continue
    for pos in range(section[4], section[4] + section[5], section[9]):
        address, info, addend = struct.unpack_from('<QQq', binary, pos)
        kind, index = info & 0xffffffff, info >> 32
        if kind == 8:
            value = base + addend
        elif kind in (1, 6, 7):
            value = symbol_address(index) + addend
        else:
            continue
        struct.pack_into('<Q', image, address, value)

# Pure, independent scalar-key Ed25519 formulation for comparison with native output.
p = 2**255 - 19
order = 2**252 + 27742317777372353535851937790883648493
d = -121665 * pow(121666, -1, p) % p
by = 4 * pow(5, -1, p) % p
x2 = (by*by - 1) * pow(d*by*by + 1, -1, p) % p
bx = pow(x2, (p+3)//8, p)
if bx*bx % p != x2:
    bx = bx * pow(2, (p-1)//4, p) % p
if bx & 1:
    bx = p - bx

def add(a, b):
    x,y = a; u,v = b
    t = d*x*y*u*v % p
    return ((x*v+y*u)*pow(1+t,-1,p)%p, (y*v+x*u)*pow(1-t,-1,p)%p)
def mul(n):
    point = (0,1); q = (bx,by)
    while n:
        if n&1:
            point = add(point,q)
        q = add(q,q); n >>= 1
    return point

def encode(point):
    x,y = point
    return (y | ((x&1)<<255)).to_bytes(32,'little')
def independent_sign(secret, public, message):
    nonce = int.from_bytes(hashlib.sha512(secret + message).digest(),'little') % order
    first = encode(mul(nonce))
    challenge = int.from_bytes(hashlib.sha512(first + public + message).digest(),'little') % order
    scalar = (nonce + challenge * int.from_bytes(secret,'little')) % order
    return first + scalar.to_bytes(32,'little')

# Initialize only portable curve field arithmetic dispatch, not client globals.
ctypes.CFUNCTYPE(None)(base + 0x47534e0)()
native_sign = ctypes.CFUNCTYPE(None,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_size_t,ctypes.c_void_p,ctypes.c_void_p)(base + 0x474bab0)
print('Mapped native image at', hex(base), file=sys.stderr, flush=True)
vectors = []
for value in (1, 7, 0x123456789abcdef):
    secret = value.to_bytes(32,'little')
    public = encode(mul(value))
    message = bytes(range(32)) + b'MyTeamSpeakID'
    output = ctypes.create_string_buffer(64)
    native_sign(output,ctypes.create_string_buffer(message),len(message),ctypes.create_string_buffer(public),ctypes.create_string_buffer(secret))
    signature = output.raw
    assert signature == independent_sign(secret,public,message), 'Native scalar-key signing differs from the independent formulation'
    vectors.append(dict(secretScalar=secret.hex(),publicKey=public.hex(),message=message.hex(),signature=signature.hex()))
print(json.dumps(dict(synthetic=True, nativeBinarySHA256=hashlib.sha256(binary).hexdigest(),routine='0x474bab0',vectors=vectors),indent=2))

