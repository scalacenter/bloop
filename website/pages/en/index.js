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
  <div className="hero">
    <div className="hero__container">

      <Logo img_src={imgUrl('orca-whale-white.svg')} />
      <h1>
        Bloop is a Scala build server.
      </h1>
      <p>
        Compile, test and run Scala fast.
      </p>
    </div>
  </div>
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

const TldrSection = ({ language }) => (
  <div className="tldrSection productShowcaseSection lightBackground">
    <Container>
      <div
        style={{
          display: "flex",
          flexFlow: "row wrap",
          justifyContent: "space-evenly"
        }}
      >
        <div style={{ display: "flex", flexDirection: "column" }}>
          <h2>Features</h2>
          <ul className="featureSection" style={{ flex: "1" }}>
            <li>Provides <b>fast</b> compile, test and run</li>
            <li>Has a built-in command-line tool</li>
            <li>Integrates with most JVM build tools</li>
            <li>Implements the <b><a href="https://github.com/scalacenter/bsp">Build Server Protocol</a></b></li>
            <li>Supports JVM, Scala.js and Scala Native</li>
          </ul>
        </div>
        <div style={{ display: "flex", flexDirection: "column" }}>
          <h2>Why?</h2>
          <ul className="whySection" style={{ flex: "1" }}>
            <li>You edit code and get instant feedback </li>
            <li>Saves you time and makes you more productive</li>
            <li>Makes Scala support in build tools easier</li>
            <li>Adaptable to your own developer workflow</li>
          </ul>
        </div>
      </div>
    </Container>
  </div>
);

TldrSection.propTypes = {
  language: PropTypes.string
};


const Features1 = () => (
  <Block layout="twoColumn">
    {[
      {
        title: 'Use it with your favorite build tool',
        content: 'Bloop supports the most popular JVM build tools in the Java and Scala community, with more integrations on their way! Export your build and benefit from faster compile times.',
      },
      {
        content:
           "|            | Supported     |\n" +
           "| -----------|-------------- |\n" +
           "| **sbt**    | ✅            |\n" +
           "| **Maven**  | ✅            |\n" +
           "| **Gradle** | ✅            |\n" +
           "| **mill**   | ✅            |\n"
      },
    ]}
  </Block>
);

const intellijLogo = imgUrl("intellij-logo.png")
const Features2 = () => (
  <Block id="ideLogos" layout="threeColumn">
    {[
      {
        content: '![Editors logos](' + imgUrl('editors-logos.svg') + ')'
      },
      {
        title: 'Use it with IDEs',
        content: 'Bloop integrates with IDEs and text editors to provide a short feedback cycle and reliable compiler diagnostics. Use Bloop with IntelliJ and [Metals](https://metals.rocks) (Visual Studio Code, Sublime Text, `vim` and Atom).',
      },
    ]}
  </Block>
);


const FeatureCallout = () => (
  <div
    className="productShowcaseSection paddingBottom"
    style={{textAlign: 'center'}}>
    <h2>Maintained by the Scala Center</h2>
    <MarkdownBlock>
    The Scala Center is a non-profit organization established at EPFL (École Polytechnique Federal de Lausanne) with the goals of promoting, supporting, and advancing the Scala language.
    </MarkdownBlock>
    <img
      src={imgUrl('scala-center-logo.png')}
      style={{paddingTop: '30px'}}></img>
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
          <Features2 />
          <FeatureCallout />
        </div>
      </div>
    );
  }
}

module.exports = Index;
