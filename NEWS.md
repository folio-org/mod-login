## 2022-10-25 v7.8.0

* [MODLOGIN-193](https://issues.folio.org/browse/MODLOGIN-193) Allow users interface to 14 thru 16.0
* [MODLOGIN-197](https://issues.folio.org/browse/MODLOGIN-197) Upgrade to RMB 35.0.2, Vert.x 4.3.4

## 2022-06-14 v7.7.0

* [MODLOGIN-190](https://issues.folio.org/browse/MODLOGIN-190) Upgrade to RMB 34.0.0
* [MODLOGIN-187](https://issues.folio.org/browse/MODLOGIN-187) Update outdated mod-users submodule from v15.4.0 to v18.2.0

## 2022-02-18 v7.6.0

Changes:

* [MODLOGIN-163](https://issues.folio.org/browse/MODLOGIN-163) POST `/authn/login` response contains clear text password.
The new behavior is to return the token in the response rather than echoing the password. Interface `login` bumped from 7.0 to 7.1.
This will make the interaction with login a little more regular.

Fixes:

* [MODLOGIN-184](https://issues.folio.org/browse/MODLOGIN-184) CQL query encoded incorrectly
* [MODLOGIN-183](https://issues.folio.org/browse/MODLOGIN-183) Upgrade to RMB 33.2.5, Log4j 2.17.
* [MODLOGIN-181](https://issues.folio.org/browse/MODLOGIN-181) 500 returned when `login.fail.to.warn.attempts` isn't configured & login fails more than once
* [MODLOGIN-177](https://issues.folio.org/browse/MODLOGIN-177) auth/login case-insensitivity should be documented
* [MODLOGIN-171](https://issues.folio.org/browse/MODLOGIN-171) Logs ERROR when using default config values
* [MODLOGIN-170](https://issues.folio.org/browse/MODLOGIN-170) Missing optional on configuration interface
* [MODLOGIN-168](https://issues.folio.org/browse/MODLOGIN-168) POST to `/authn/login` after too many failed requests generates 500
* [MODLOGIN-157](https://issues.folio.org/browse/MODLOGIN-157) Unhandled exception for `/authn/login`
* [MODLOGIN-155](https://issues.folio.org/browse/MODLOGIN-155) Missing dependency on `mod-authtoken`

## 2021-12-15 v7.5.1

* [MODLOGIN-174](https://issues.folio.org/browse/MODLOGIN-174) RMB 33.2.2, Log4j 2.16.0
* [MODLOGIN-172](https://issues.folio.org/browse/MODLOGIN-172) RMB 33.2.1, Vertx 4.2.1, Log4j 2.15.0 fixing remote execution (CVE-2021-44228)
* [MODLOGIN-165](https://issues.folio.org/browse/MODLOGIN-165) Incorrect handling of user name term

## 2021-09-29 v7.5.0

* [MODLOGIN-159](https://issues.folio.org/browse/MODLOGIN-159) RMB 33.1.1, Vert.x 4.1.4

## 2021-05-28 v7.4.0

* [MODLOGIN-158](https://issues.folio.org/browse/MODLOGIN-158) RMB 33.0.0, Vert.x 4.1.0.CR1

## 2021-05-24 v7.3.0

* [MODLOGIN-142](https://issues.folio.org/browse/MODLOGIN-142) Add personal data disclosure form

## 2021-01-14 v7.2.0

* [MODLOGIN-146](https://issues.folio.org/browse/MODLOGIN-146) Upgrade mod-login to RMB v32.x

## 2020-10-06 v7.1.0

* [MODLOGIN-137](https://issues.folio.org/browse/MODLOGIN-137) Upgrade to RMB 31.x and JDK 11

## 2020-08-28 v7.0.1
* [MODLOGIN-135](https://issues.folio.org/browse/MODLOGIN-135) Upgrade raml-module-builder (RMB) from 30.0.0 to 30.2.6

## 2020-06-10 v7.0.0
The main focus of this release is to address several security issues.

[Full Changelog](https://github.com/folio-org/mod-login/compare/v6.2.0...v7.0.0)

### Stories
* [MODLOGIN-127](https://issues.folio.org/browse/MODLOGIN-127) - Upgrade to RMB 30.0.0
* [MODLOGIN-130](https://issues.folio.org/browse/MODLOGIN-130) - Provide permissionsRequired property
* [MODLOGIN-132](https://issues.folio.org/browse/MODLOGIN-132) - Do not return credential hash/salt when posting credentials
* [MODLOGIN-133](https://issues.folio.org/browse/MODLOGIN-133) - Remove PUT /authn/credentials/{id}
* [MODLOGIN-134](https://issues.folio.org/browse/MODLOGIN-134) - Refactor DELETE /authn/credentials/{id}

### Bugs
* [MODLOGIN-24](https://issues.folio.org/brows/MODLOGIN-24) - POST /authn/credentials returns 500 when password is missing
* [MODLOGIN-128](https://issues.folio.org/browse/MODLOGIN-128) - It is possible to fetch password hashes for all users
* [MODLOGIN-129](https://issues.folio.org/browse/MODLOGIN-129) - POST to /authn/credentials accepts empty string for password
* [MODLOGIN-131](https://issues.folio.org/browse/MODLOGIN-131) - Reset password fails if credentials record does not already exist

## 2019-12-04 v6.2.0
 * [MODLOGIN-122](https://issues.folio.org/browse/MODLOGIN-122) Upgrade to RMB 29

## 2019-09-24 v6.1.0
 * [MODLOGIN-116](https://issues.folio.org/browse/MODLOGIN-116) Fix "Multiple HttpClient objects are created for every
   login attempt causing CPU usages to spike"

## 2019-07-24 v6.0.0
 * [MODLOGIN-112](https://issues.folio.org/browse/MODLOGIN-112) update RMB version to 25.0.1
 * [MODLOGIN-114](https://issues.folio.org/browse/MODLOGIN-114) Change the implementation of password checking for repeatability

## 2019-06-11 v5.3.0
 * [MODLOGIN-111](https://issues.folio.org/browse/MODLOGIN-111) mod-login requires users interface
   (how on earth would it valudate a user anyway?)

## 2019-05-10 v5.2.0
 * [MODLOGIN-110](https://issues.folio.org/browse/MODLOGIN-110) Update to RMB 24 / CQLPG 4.0.0
 * [MODLOGIN-108](https://issues.folio.org/browse/MODLOGIN-108) Change limit from 1000 to 2147483647 (login.raml)

## 2019-03-15 v5.1.0
 * [MODLOGIN-105](https://issues.folio.org/browse/MODLOGIN-105)	use loadSample to load sample data

## 2019-03-05 v5.0.0
 * Update to RMB 23.9.0 ([MODLOGIN-106](https://issues.folio.org/browse/MODLOGIN-106))
 * Use `x-okapi-requet-ip` header if `X-Forwarded-For` is not present
   ([MODLOGIN-104](https://issues.folio.org/browse/MODLOGIN-104))
 * Use EventsLogAPI ([MODLOGIN-77](https://issues.folio.org/browse/MODLOGIN-77))
 * Handle failed login attempts. This changes provided login interface
   from 4.7 to 5.0 ([MODLOGIN-103](https://issues.folio.org/browse/MODLOGIN-103)).
 * Implementing endpoint for checking credentials existence ([UIU-758](https://issues.folio.org/browse/UIU-758))
 * Create/Extend password storage to support retaining last 10 changed
   passwords a user has saved ([MODLOGIN-86](https://issues.folio.org/browse/MODLOGIN-86))
 * Add and Implement new "EventsLogApi" component ([MODLOGIN-75](https://issues.folio.org/browse/MODLOGIN-75))
 * Fix user blocking ([MODLOGIN-89](https://issues.folio.org/browse/MODLOGIN-89))

## 2018-12-03 v4.6.0
 * Set Content-Type and Accept headers for outgoing requests ([MODLOGIN-92](https://issues.folio.org/browse/MODLOGIN-92))
 * Update to RAML 1.0 ([MODLOGIN-80](https://issues.folio.org/browse/MODLOGIN-80))

## 2018-11-19 v4.6.0
 * Add implementation /authn/password-reset-action which is accessible through OKAPI
 * Add rest tests

 API:

 | METHOD |  URL                                    | DESCRIPTION                                                     |
 |--------|-----------------------------------------|-----------------------------------------------------------------|
 | POST   | /authn/reset-password                   | Resets password for user in record and deletes action record    |
 | POST   | /authn/password-reset-action            | Saves action to storage                                         |
 | GET    | /authn/password-reset-action/{actionId} | Retrieves action record by id                                   |

## 2018-09-12 v4.5.0
 * Restructure RAML to make module RAML and JSON local to repo
 * Return refresh token in login response when available

## 2018-09-10 v4.4.0
 * Accomodate either 200 or 201 response from authtoken creation request

## 2018-08-07 v4.3.1
 * Add logs for successful or fail login attempts
 * Add logs for event when user is blocked after n fail login attempts
 * Improved login attempt's tests

## 2018-08-07 v4.3.0
 * Enable option to block user account after fail logins
 * Enable option to set number of login attempts before blocking account

## 2018-08-01 v4.2.1
 * Add /authn/update info to module descriptor

## 2018-07-30 v4.2.0
 * Add /authn/update endpoint to allow self-updating of credentials

## 2018-07-05 v4.1.0
 * Enable option to refuse login when associated user record is flagged as inactive
 * Enable option to set the timeout (in ms) for looking up associated user records

## 2016-07-28 v3.1.0
 * Include user-id field when generating authtoken on login
 * Use more specific relation '==' when looking up users

## 2016-07-27 v3.0.3
 * Add version to id in ModuleDescriptor

## 2017-06-26 v3.0.2
 * Handle updated 'totalRecords' field when contacting mod-users

## 2017-06-12 v3.0.1
 * Update RMB to 12.1.3

## 2017-05-10
 * Change RMB dependency to 11.0.0
