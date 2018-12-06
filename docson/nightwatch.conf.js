module.exports = {
  "src_folders" : ["nightwatch"],
  "output_folder" : "reports",
  "selenium" : {
    "start_process" : false,
//    "server_path" : "./bin/selenium-server-standalone-3.8.1.jar",
    "port" : 4444,
    "cli_args" : {
      "webdriver.gecko.driver" : "./bin/geckodriver"
    }
  },

  "test_settings" : {
    "default" : {
      "launch_url" : "http://localhost",
      "selenium_port"  : 4444,
      "selenium_host"  : "localhost",
      "silent": true,
      "screenshots" : {
        "enabled" : false,
        "path" : ""
      },
      "desiredCapabilities": {
        "browserName": "firefox",
        "marionette": true
      }
    },

    "chrome" : {
      "desiredCapabilities": {
        "browserName": "chrome"
      }
    }

  }
}
