/**
 * Copyright (c) 2017-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

const React = require("react");

const siteConfig = require(process.cwd() + "/siteConfig.js");

class Footer extends React.Component {
  render() {
    const {
      baseUrl,
      copyright,
      credits1,
      credits2,
      colors: { primaryColor }
    } = this.props.config;
    const docsUrl = `${baseUrl}docs/`;
    return (
      <footer
        className="nav-footer"
        id="footer"
        style={{ backgroundColor: primaryColor }}
      >
        <section className="sitemap">
          {this.props.config.footerIcon && (
            <a href={this.props.config.baseUrl} className="nav-home">
              <img
                src={`${this.props.config.baseUrl}${this.props.config.footerIcon}`}
                alt={this.props.config.title}
                height="60"
              />
            </a>
          )}
          {this.props.config.scalaIcon && (
            <a href={this.props.config.baseUrl} className="nav-home">
              <img
                src={`${this.props.config.baseUrl}${this.props.config.scalaIcon}`}
                alt={this.props.config.title}
                height="60"
              />
            </a>
          )}
          <div>
            <h5>Overview</h5>
            <a href={`${docsUrl}what-is-bloop`}>What is Bloop</a>
            <a href={`${docsUrl}integration`}>Integrate with Bloop</a>
            <a href={`${docsUrl}build-tools/overview`}>Build Tools</a>
            <a href={` ${docsUrl}contributing-guide`}>
              Contributing
            </a>
          </div>
          <div>
            <h5>Build Tools</h5>
            <a href={`${docsUrl}build-tools/sbt`}>sbt</a>
            <a href={`${docsUrl}build-tools/gradle`}>Gradle</a>
            <a href={`${docsUrl}build-tools/maven`}>Maven</a>
            <a href={`${docsUrl}build-tools/mill`}>Mill</a>
          </div>
          <div>
            <h5>Social</h5>
            <a href="https://github.com/scalacenter/bloop" target="_blank">
              <img src="https://img.shields.io/github/stars/scalacenter/bloop.svg?color=%23087e8b&label=stars&logo=github&style=social" />
            </a>
            <a href="https://discord.gg/vys6d43" target="_blank">
              <img src="https://img.shields.io/discord/632642981228314653?logo=discord&style=social" />
            </a>
            <a href="https://gitter.im/scalacenter/bloop" target="_blank">
              <img src="https://img.shields.io/gitter/room/scalacenter/bloop.svg?logo=gitter&style=social" />
            </a>
            <a href="https://twitter.com/jvican" target="_blank">
              <img src="https://img.shields.io/twitter/follow/jvican.svg?logo=twitter&style=social" />
            </a>
          </div>
        </section>
        <section className="copyright">{copyright}</section>
        <section className="copyright">{credits1}</section>
        <section className="copyright">{credits2}</section>
      </footer>
    );
  }
}

module.exports = Footer;