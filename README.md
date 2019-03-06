# mod-login

Copyright (C) 2016-2019 The Open Library Foundation

This software is distributed under the terms of the Apache License,
Version 2.0. See the file "[LICENSE](LICENSE)" for more information.

# Introduction

This module is responsible for verifying the user's identity and
issuing a valid JWT that can be used for system access. The implementation of
this module may vary (username/password, SAML, OAuth, etc.), and it is possible
for more than one Authentication module to exist in a running system. The
default implementation uses a simple username and password for authentication.

# Additional information

The [raml-module-builder](https://github.com/folio-org/raml-module-builder) framework.

Other [modules](https://dev.folio.org/source-code/#server-side).

See project [MODLOGIN](https://issues.folio.org/browse/MODLOGIN)
at the [FOLIO issue tracker](https://dev.folio.org/guidelines/issue-tracker).

Other FOLIO Developer documentation is at [dev.folio.org](https://dev.folio.org/)

# Module properties to set up at mod-configuration
* login.fail.attempts - number of login attempts before block user account (default value - 5)
* login.fail.timeout - after timeout in minutes, fail login attempts will be dropped (default value - 10)
