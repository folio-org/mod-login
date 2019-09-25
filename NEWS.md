## 2019-09-25 v6.1.1
 * MODLOGIN-117 Update to RMB 25.0.2 . Fixes security issue / FOLIO-2281

## 2019-09-24 v6.1.0
 * MODLOGIN-116 Fix "Multiple HttpClient objects are created for every
   login attempt causing CPU usages to spike"

## 2019-07-24 v6.0.0
 * MODLOGIN-112 update RMB version to 25.0.1
 * MODLOGIN-114 Change the implementation of password checking for repeatability

## 2019-06-11 v5.3.0
 * MODLOGIN-111 mod-login requires users interface
   (how on earth would it valudate a user anyway?)

## 2019-05-10 v5.2.0
 * MODLOGIN-110 Update to RMB 24 / CQLPG 4.0.0
 * MODLOGIN-108 Change limit from 1000 to 2147483647 (login.raml)

## 2019-03-15 v5.1.0
 * MODLOGIN-105	use loadSample to load sample data

## 2019-03-05 v5.0.0
 * Update to RMB 23.9.0 (MODLOGIN-106)
 * Use `x-okapi-requet-ip` header if `X-Forwarded-For` is not present
   (MODLOGIN-104)
 * Use EventsLogAPI (MODLOGIN-77)
 * Handle failed login attempts. This changes provided login interface
   from 4.7 to 5.0 (MODLOGIN-103).
 * Implementing endpoint for checking credentials existence (UIU-758)
 * Create/Extend password storage to support retaining last 10 changed
   passwords a user has saved (MODLOGIN-86)
 * Add and Implement new "EventsLogApi" component (MODLOGIN-75)
 * Fix user blocking (MODLOGIN-89)

## 2018-12-03 v4.6.0
 * Set Content-Type and Accept headers for outgoing requests (MODLOGIN-92)
 * Update to RAML 1.0 (MODLOGIN-80)

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
