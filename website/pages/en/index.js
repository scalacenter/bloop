/**
 * Copyright (c) 2017-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

const React = require('react');
const PropTypes = require("prop-types");

const CompLibrary = require('../../core/CompLibrary.js');

const MarkdownBlock = CompLibrary.MarkdownBlock; /* Used to read markdown */
const Container = CompLibrary.Container;
const GridBlock = CompLibrary.GridBlock;

const siteConfig = require(`${process.cwd()}/siteConfig.js`);

function repoUrl() {
  return `https://github.com/${siteConfig.organizationName}/${
    siteConfig.projectName
    }`;
}

function imgUrl(img) {
  return `${siteConfig.baseUrl}img/${img}`;
}

function docUrl(doc, language) {
  return `${siteConfig.baseUrl}docs/${language ? `${language}/` : ''}${doc}`;
}

function pageUrl(page, language) {
  return siteConfig.baseUrl + (language ? `${language}/` : '') + page;
}

class Button extends React.Component {
  render() {
    return (
      <div className="pluginWrapper buttonWrapper">
        <a className="button" href={this.props.href} target={this.props.target}>
          {this.props.children}
        </a>
      </div>
    );
  }
}

Button.defaultProps = {
  target: '_self',
};

const SplashContainer = props => (
  <div className="homeContainer">
    <div className="homeSplashFade">
      <div className="wrapper homeWrapper">{props.children}</div>
    </div>
  </div>
);

const Logo = props => (
  <div className="projectLogo">
    <img src={props.img_src} alt="Project Logo" />
  </div>
);

const ProjectTitle = () => (
  <h2 className="projectTitle">
    {siteConfig.title}
    <small>{siteConfig.tagline}</small>
  </h2>
);

const PromoSection = props => (
  <div className="section promoSection">
    <div className="promoRow">
      <div className="pluginRowBlock">{props.children}</div>
    </div>
  </div>
);

const announcement =
  <div className="hero__announcement">
    <span>
      <strong>Bloop 1.1.0 is out!</strong> Please read our{" "}
      <a href="">announcement</a> and{" "}
      <a href="">upgrade guide</a>{" "}
      for more information.
    </span>
  </div>

const Hero = ({ language }) => (
  <div className="hero lightBackground">
    <div className="hero__container">
      <Container>
        <div
          style={{
            display: "flex",
            flexFlow: "row wrap",
            alignItems: "center",
            margin: "0 auto"
          }}
        >
          <div className="hero__promo" style={{ display: "flex", flexDirection: "column" }}>
            <h3 style={{ marginLeft: "0.5rem" }}>
              {"One toolchain, one build server, one developer experience"}
            </h3>
            <h2>
              Build and test <b>Scala</b> code of any size <b>quickly</b>, from any editor, from any build tool
            </h2>
            <PromoSection>
              <Button href="#try">Try It Out</Button>
              <Button href={docUrl('doc1.html', language)}>Get Started</Button>
            </PromoSection>
          </div>
          <div className="hero__logo" style={{ display: "flex", flexDirection: "column" }}>
            <Logo img_src={imgUrl('impure-logo-bloop.svg')} />
          </div>
        </div>
      </Container>
    </div>
  </div >
);

class HomeSplash extends React.Component {
  render() {
    const language = this.props.language || '';
    return (
      <SplashContainer>
        <Logo img_src={imgUrl('logo.svg')} />
        <div className="inner">
          <ProjectTitle />
          <PromoSection>
            <Button href="#try">Try It Out</Button>
            <Button href={docUrl('doc1.html', language)}>Get Started</Button>
          </PromoSection>
        </div>
      </SplashContainer>
    );
  }
}

const Block = props => (
  <Container
    padding={['bottom', 'top']}
    id={props.id}
    background={props.background}>
    <GridBlock align="center" contents={props.children} layout={props.layout} />
  </Container>
);

const TopFeatures = () => (
  <Block layout="fourColumn">
    {[
      {
        title: 'Tighter developer workflow',
        content: 'Be more productive in Scala and reduce your compile-edit-test workflow. Use Bloop to enjoy an optimized developer experience that provides features from incremental to batch compilation, from running and debugging on the JVM to building Scala.js or Scala Native applications.',
        image: imgUrl('clock.svg'),
        imageAlign: 'bottom'
      },
      {
        title: 'One tool, multiple editors and IDEs',
        content: 'Bloop integrates with IDEs and text editors to provide a short feedback cycle and reliable compiler diagnostics. Use Bloop with IntelliJ or [Metals](https://scalameta.org/metals/), an LSP server to code with editors such as Visual Studio Code, Sublime Text, Vim, Emacs, etc.',
        image: imgUrl('editors-logos.svg'),
        imageAlign: 'bottom',
        imageClassName: 'editorLogos',
      },
      {
        title: 'Compatible with many build tools',
        content: 'Export your project build to Bloop even if your build tool lacks Bloop support. Everything in Bloop has been thought to be build-tool-agnostic and bring you the best Scala developer experience, no matter what tool you use.',
        extra:
          "|            | Supported     |\n" +
          "| -----------|-------------- |\n" +
          "| **sbt**    | ✅            |\n" +
          "| **Maven**  | ✅            |\n" +
          "| **Gradle** | ✅            |\n" +
          "| **mill**   | ✅            |\n" +
          "| **Pants**  | ✅            |\n"
      },
      {
        title: 'Extensible to your custom needs',
        content: 'Customize Bloop to serve your personal needs or those of your company. Use the CLI to write custom scripts, write your own build tool on top of bloop or leverage the [Build Server Protocol](https://build-server-protocol.github.io) implementation to control and extend Bloop with your own build client, in any language.',
        image: imgUrl('sew.svg'),
        imageAlign: 'bottom'
      }
    ]}
  </Block>
);

const TldrSection = ({ language }) => (
  <div className="tldrSection productShowcaseSection">
    <Container>
      <div
        style={{
          display: "flex",
          flexFlow: "row wrap",
          justifyContent: "space-evenly"
        }}
      >
        <div style={{ display: "flex", flexDirection: "column" }}>
          <h1>Why Bloop?</h1>
        </div>
      </div>
      <TopFeatures>
      </TopFeatures>
    </Container>
  </div>
);

TldrSection.propTypes = {
  language: PropTypes.string
};


const Features1 = () => (
  <div className="featuresShowcaseSection">
    <Block layout="twoColumn">
      {[
        {
          title: 'Reimagining what a build server is with Bloop',
          content: "Bloop is a build server that runs in the backgroud of your machine and serves build requests for a specific workspace. As it knows how your build workspace is being built by every client, it can optimize and provide guarantees that conventional build tools cannot. Bloop has taken the concept of build server and stepped it up a notch, enabling you to use it in ways that haven't yet been fully explored.",
        },
        {
          title: 'Run multiple build tasks, concurrently, from any clients',
          content: "Have you ever wanted to test your code from your IDE and run a main class on your terminal whenever there are changes? Bloop allows you to have multiple build clients (IDEs, build tools, custom scripts, scheduled jobs) triggering build commands at the same time, while the build server makes sure all build commands produce independent outputs, reuse as much state and resources as they can and don't block each other.",
        },
      ]}
    </Block>
  </div>
);

const FeatureCallout = () => (
  <div
    className="productShowcaseSection paddingBottom"
    style={{ textAlign: 'center' }}>
    <h2>Maintained by a rich community of contributors</h2>
    <MarkdownBlock>
      Developed initially at the Scala Center —a non-profit organization
      established at EPFL with the goals of promoting, supporting, and
      advancing the Scala language— the project has grown to be adopted by
      many industry leaders and it is now maintained by a dedicated network
      of contributors across the world.
    </MarkdownBlock>
  </div>
);

const LearnHow = () => (
  <Block>
    {[
      {
        content: 'Talk about learning how to use this',
        image: imgUrl('docusaurus.svg'),
        imageAlign: 'right',
        title: 'Learn How',
      },
    ]}
  </Block>
);

const TryOut = () => (
  <Block id="try">
    {[
      {
        content: 'Talk about trying this out',
        image: imgUrl('docusaurus.svg'),
        imageAlign: 'left',
        title: 'Try it Out',
      },
    ]}
  </Block>
);

const Description = () => (
  <Block background="dark">
    {[
      {
        content: 'This is another description of how this project is useful',
        image: imgUrl('docusaurus.svg'),
        imageAlign: 'right',
        title: 'Description',
      },
    ]}
  </Block>
);

const Showcase = props => {
  if ((siteConfig.users || []).length === 0) {
    return null;
  }

  const showcase = siteConfig.users.filter(user => user.pinned).map(user => (
    <a href={user.infoLink} key={user.infoLink}>
      <img src={user.image} alt={user.caption} title={user.caption} />
    </a>
  ));

  return (
    <div className="productShowcaseSection paddingBottom">
      <h2>Who is Using This?</h2>
      <p>This project is used by all these people</p>
      <div className="logos">{showcase}</div>
      <div className="more-users">
        <a className="button" href={pageUrl('users.html', props.language)}>
          More {siteConfig.title} Users
        </a>
      </div>
    </div>
  );
};

class Index extends React.Component {
  render() {
    const language = this.props.language || '';

    return (
      <div>
        <Hero language={language} />
        <TldrSection language={language} />
        <div className="mainContainer">
          <Features1 />
          <FeatureCallout />
        </div>
      </div>
    );
  }
}

module.exports = Index;
