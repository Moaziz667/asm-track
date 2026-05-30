import bcrypt
hash = bcrypt.hashpw(b'admin', bcrypt.gensalt()).decode('utf-8')
print(hash)
