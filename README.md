# mod-login

Copyright (C) 2016-2024 The Open Library Foundation

This software is distributed under the terms of the Apache License,
Version 2.0. See the file "[LICENSE](LICENSE)" for more information.

# Introduction

This module is responsible for verifying the user's identity and
issuing a valid JWT that can be used for system access. The implementation of
this module may vary (username/password, SAML, OAuth, etc.), and it is possible
for more than one Authentication module to exist in a running system. The
default implementation uses a simple username and password for authentication.

# Environment variables

* LOGIN_COOKIE_SAMESITE - Configures the SameSite attribute of the login token cookies. Must be `Strict`, `Lax` or `None`. Defaults to `Lax` if not set.

# Module properties

The following module parameters can be specified on the command line.
See <https://github.com/folio-org/raml-module-builder#command-line-options>
When specified, these will take precedence over the hard-coded defaults.

* login.fail.to.warn.attempts - number of login attempts before warn (default value - 3)
* login.fail.attempts - number of login attempts before block user account (default value - 5)
* login.fail.timeout - after timeout in minutes, fail login attempts will be dropped (default value - 10)

# Mod-configuration entries

The following configuration entries can be specified in mod-configuration.
When present, these will take precedence over the hard-coded defaults and
the module parameter values specified on the command line.

| module    | code                        | configName           | Description                                                                         |
|:----------|:----------------------------|:---------------------|:------------------------------------------------------------------------------------|
| EVENT_LOG | STATUS                      | {any}                | Enable/disable event logging.  If disabled, events will not be logged, nor will you be able to retreive previously logged entries (default: enabled=false) |
| EVENT_LOG | SUCESSFUL_LOGIN_ATTEMPT     | {any}                | If enabled, log successful login attempts to the event log (default: enabled=false) |
| EVENT_LOG | FAILED_LOGIN_ATTEMPT        | {any}                | If enabled, log failed login attempts to the event log (default: enabled=false)     |
| EVENT_LOG | PASSWORD_RESET              | {any}                | If enabled, log password reset events to the event log (default: enabled=false)     |
| EVENT_LOG | PASSWORD_CREATE             | {any}                | If enabled, log password creation events to the event log (default: enabled=false)  |
| EVENT_LOG | PASSWORD_CHANGE             | {any}                | If enabled, log password change events to the event log (default: enabled=false)    |
| EVENT_LOG | USER_BLOCK                  | {any}                | If enabled, log user blocked events to the event log (default: enabled=false)       |
| {any}     | login.fail.attempts         | {any}                | Number of login attempts before block user account (default: value=5)               |
| {any}     | login.fail.to.warn.attempts | {any}                | Number of login attempts before warn (default: value=3)                             |
| {any}     | login.fail.timeout          | {any}                | After timeout in minutes, fail login attempts will be dropped (default: value=10)   |
| {any}     | {any}                       | login.history.number | Number of previously used passwords which should factor into the "has this password been previously used" check (default: value=10) |

# Additional information

The [raml-module-builder](https://github.com/folio-org/raml-module-builder) framework.

Other [modules](https://dev.folio.org/source-code/#server-side).

Other FOLIO Developer documentation is at [dev.folio.org](https://dev.folio.org/)

### Issue tracker

See project [MODLOGIN](https://issues.folio.org/browse/MODLOGIN)
at the [FOLIO issue tracker](https://dev.folio.org/guidelines/issue-tracker).

### ModuleDescriptor

See the built `target/ModuleDescriptor.json` for the interfaces that this module
requires and provides, the permissions, and the additional module metadata.

### API documentation

This module's [API documentation](https://dev.folio.org/reference/api/#mod-login).

### Code analysis

[SonarQube analysis](https://sonarcloud.io/dashboard?id=org.folio%3Amod-login).

### Download and configuration

The built artifacts for this module are available.
See [configuration](https://dev.folio.org/download/artifacts) for repository access,
and the [Docker image](https://hub.docker.com/r/folioorg/mod-login/).

