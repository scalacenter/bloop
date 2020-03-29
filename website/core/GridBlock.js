/**
 * Copyright (c) 2017-present, Facebook, Inc.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

const React = require('react');
const classNames = require('classnames');

const MarkdownBlock = require('docusaurus/lib/core/MarkdownBlock.js');

class GridBlock extends React.Component {
  renderBlock(origBlock) {
    const blockDefaults = {
      imageAlign: 'left',
    };

    const block = {
      ...blockDefaults,
      ...origBlock,
    };

    const blockClasses = classNames('blockElement', this.props.className, {
      alignCenter: this.props.align === 'center',
      alignRight: this.props.align === 'right',
      fourByGridBlock: this.props.layout === 'fourColumn',
      imageAlignSide:
        block.image &&
        (block.imageAlign === 'left' || block.imageAlign === 'right'),
      imageAlignTop: block.image && block.imageAlign === 'top',
      imageAlignRight: block.image && block.imageAlign === 'right',
      imageAlignBottom: block.image && block.imageAlign === 'bottom',
      imageAlignLeft: block.image && block.imageAlign === 'left',
      threeByGridBlock: this.props.layout === 'threeColumn',
      twoByGridBlock: this.props.layout === 'twoColumn',
    });

    const topLeftImage =
      (block.imageAlign === 'top' || block.imageAlign === 'left') &&
      this.renderBlockImage(block.image, block.imageLink, block.imageAlt, block.imageClassName);

    const bottomRightImage =
      (block.imageAlign === 'bottom' || block.imageAlign === 'right') &&
      this.renderBlockImage(block.image, block.imageLink, block.imageAlt, block.imageClassName);

    const extra = block.extra

    return (
      <div className={blockClasses} key={block.title}>
        {topLeftImage}
        <div className="blockContent">
          {this.renderBlockTitle(block.title)}
          <MarkdownBlock>{block.content}</MarkdownBlock>
        </div>
        {this.renderBlockExtra(block)}
        {bottomRightImage}
      </div>
    );
  }

  renderBlockExtra(block) {
    if (!block.extra) {
      return null;
    }

    return (
      <div className="blockElement">
        <MarkdownBlock>{block.extra}</MarkdownBlock>
      </div>
    );
  }

  renderBlockImage(image, imageLink, imageAlt, imageClassName) {
    if (!image) {
      return null;
    }

    const blockClasses = classNames('blockImage', imageClassName);

    return (
      <div className={blockClasses}>
        {imageLink ? (
          <a href={imageLink}>
            <img src={image} alt={imageAlt} />
          </a>
        ) : (
          <img src={image} alt={imageAlt} />
        )}
      </div>
    );
  }

  renderBlockTitle(title) {
    if (!title) {
      return null;
    }

    return (
      <h2>
        <MarkdownBlock>{title}</MarkdownBlock>
      </h2>
    );
  }

  render() {
    return (
      <div className="gridBlock">
        {this.props.contents.map(this.renderBlock, this)}
      </div>
    );
  }
}

GridBlock.defaultProps = {
  align: 'left',
  contents: [],
  layout: 'twoColumn',
};

module.exports = GridBlock;
