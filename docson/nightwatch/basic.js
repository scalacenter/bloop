const c2x = require( 'css2xpath' );
let static_app = require( '../lib/server' );
let server;

const rootUrl = "http://localhost:3000/index.html";
  
module.exports = {
  before: done => {
      server = static_app.listen( 3000, done );
  },
  after: done => server.close( done ),

  'relative paths' : function (browser) {
    browser.url( rootUrl + "#/nightwatch/schemas/relative.json").pause(1000);

    browser
      .useXpath()
      .expect
      .element(c2x(':contains("a baz string")'))
      .present;

    browser.end();
  },

  'resolve #definitions in non-root schema': function(browser) {
    browser.url( rootUrl + "#/nightwatch/schemas/def-non-root/User.json").pause(1000);

    browser
      .useXpath()
      .expect
      .element(c2x('.property-name:contains("oderOfFirstLastName")'))
      .present;

    browser.end();
  },

  'local schema, absolute path': function(browser) {
    browser.url( rootUrl + "#/nightwatch/schemas/local-absolute/main.json").pause(1000);

    browser
      .useXpath()
      .expect
      .element(c2x('.desc:contains("a foo number")'))
      .present;

    browser.end();
  },

  'recursive schemas': function(browser) {
    browser.url( rootUrl + "#/nightwatch/schemas/recursive/circle.json").pause(1000);

    browser
      .useXpath()
      .expect
      .element(c2x('.desc:contains("circular reference")'))
      .present;

    browser.url( rootUrl + "#/nightwatch/schemas/recursive/within_schema.json").pause(1000);

    browser
      .useXpath()
      .expect
      .element(c2x('.desc:contains("circular definitions")'))
      .present;

    browser.end();
  },

};
