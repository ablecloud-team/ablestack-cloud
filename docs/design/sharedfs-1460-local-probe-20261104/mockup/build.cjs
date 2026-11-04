// Licensed to the Apache Software Foundation (ASF) under one or more
// contributor license agreements. See NOTICE. Apache License, Version 2.0.
// http://www.apache.org/licenses/LICENSE-2.0
const path=require('path');const req=require('module').createRequire(path.resolve(__dirname,'../../../../ui/package.json'));req('webpack')(require('./webpack.config.cjs'),(e,s)=>{if(e){console.error(e);process.exit(1)}console.log(s.toString({colors:false,modules:false}));process.exit(s.hasErrors()?1:0)});