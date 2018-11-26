## 2018-11-08 v4.6.1
 * Add implementation `/authn/log/events` which is accessible through OKAPI
 * Add rest tests

 API:

 | METHOD |  URL                          | DESCRIPTION                                                       |
 |--------|-------------------------------|-------------------------------------------------------------------|
 | POST   | /authn/log/events             | Saves the event into the storage                                  |
 | GET    | /authn/log/events/            | Get a list of events from storage                                 |
 | DELETE | /authn/log/events/{userId}    | Delete the event by userId from storage                           |

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
