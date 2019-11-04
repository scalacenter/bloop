/**
 * Copyright (c) 2017-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

const React = require('react');

const CompLibrary = require('../../core/CompLibrary');

const Container = CompLibrary.Container;

const CWD = process.cwd();

const versions = require(`${CWD}/versions.json`);

function Versions(props) {
  const { config: siteConfig } = props;
  const latestVersion = versions[0];
  const repoUrl = `https://github.com/${siteConfig.organizationName}/${
    siteConfig.projectName
    }`;
  return (
    <div className="docMainWrapper wrapper">
      <Container className="mainContainer versionsContainer">
        <div className="post">
          <header className="postHeader">
            <h1>{siteConfig.title} Versions</h1>
          </header>
          <p>New versions of this project are released every so often.</p>
          <h3 id="latest">Current version (Stable)</h3>
          <table className="versions">
            <tbody>
              <tr>
                <th>{latestVersion}</th>
                <td>
                  <a href={
                    siteConfig.baseUrl + "docs/what-is-bloop"
                  }>Documentation</a>
                </td>

                <td>
                  <a href={`${repoUrl}/releases/tag/v${latestVersion}`}>
                    Release Notes
                  </a>
                </td>
              </tr>
            </tbody>
          </table>
          <p>
            This is the version that is configured automatically when you first
            install this project.
          </p>
          <h3 id="rc">Pre-release versions</h3>
          <p>
            Every change merged in Bloop is made available in a pre-release version third parties can depend on.
            To know the latest available release, you can consult the release table in the <a href={siteConfig.baseUrl + "setup"}>Installation section</a>.
            Pre-release versions provide no guarantees with regards to correctness and performance and only intend to make available those release-worthy changes and provide a playground for maintainers and early adopters to try future changes out.
          </p>
          <table className="versions">
            <tbody>
              <tr>
                <th>master</th>
                <td>
                  <a href={
                    siteConfig.baseUrl + "docs/next/what-is-bloop"
                  }>Documentation</a>
                </td>
                <td>
                  No Release Notes
                </td>
              </tr>
            </tbody>
          </table>
          <h3 id="archive">Past Versions</h3>
          <table className="versions">
            <tbody>
              {versions.map(
                version =>
                  version !== latestVersion && (
                    <tr>
                      <th>{version}</th>
                      <td>
                        {/* You are supposed to fill this href by yourself 
                        Example: href={`docs/${version}/doc.html`} */}
                        <a href={
                          siteConfig.baseUrl + "docs/" + version + "/what-is-bloop"
                        }>Documentation</a>
                      </td>
                      <td>
                        <a href={`${repoUrl}/releases/tag/v${version}`}>
                          Release Notes
                        </a>
                      </td>
                    </tr>
                  ),
              )}
            </tbody>
          </table>
          <p>
            You can find past versions of this project on{' '}
            <a href={repoUrl}>GitHub</a>.
          </p>
        </div>
      </Container>
    </div>
  );
}

module.exports = Versions;
