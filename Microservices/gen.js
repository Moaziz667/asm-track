const bcrypt = require('bcryptjs');
console.log(bcrypt.hashSync('admin', 10));
