const path = require('path');
const {CleanWebpackPlugin} = require('clean-webpack-plugin');
const CopyWebpackPlugin = require('copy-webpack-plugin');
const ModuleFederationPlugin = require('webpack/lib/container/ModuleFederationPlugin');
const moonstone = require('@jahia/moonstone/dist/rulesconfig-wp');
const getModuleFederationConfig = require('@jahia/webpack-config/getModuleFederationConfig');
const packageJson = require('./package.json');
const {CycloneDxWebpackPlugin} = require('@cyclonedx/webpack-plugin');

/** @type {import('@cyclonedx/webpack-plugin').CycloneDxWebpackPluginOptions} */
const cycloneDxWebpackPluginOptions = {
    specVersion: '1.4',
    rootComponentType: 'library',
    outputLocation: './bom'
};

module.exports = (env, argv) => {
    const config = {
        entry: {
            main: path.resolve(__dirname, 'src/javascript/index')
        },
        output: {
            path: path.resolve(__dirname, 'src/main/resources/javascript/apps/'),
            filename: 'jahia-store.bundle.js',
            chunkFilename: '[name].jahia.[chunkhash:6].js'
        },
        resolve: {
            mainFields: ['module', 'main'],
            extensions: ['.mjs', '.js', '.jsx', '.json', '.scss'],
            fallback: {url: false}
        },
        module: {
            rules: [
                ...moonstone,
                {test: /\.m?js$/, type: 'javascript/auto'},
                {
                    test: /\.jsx?$/,
                    include: [path.join(__dirname, 'src')],
                    use: {
                        loader: 'babel-loader',
                        options: {
                            presets: [
                                ['@babel/preset-env', {
                                    modules: false,
                                    targets: {chrome: '60', edge: '44', firefox: '54', safari: '12'}
                                }],
                                '@babel/preset-react'
                            ],
                            plugins: ['@babel/plugin-syntax-dynamic-import']
                        }
                    }
                },
                {
                    test: /\.scss$/i,
                    include: [path.join(__dirname, 'src')],
                    sideEffects: true,
                    use: [
                        'style-loader',
                        {loader: 'css-loader', options: {modules: {mode: 'local'}}},
                        'sass-loader'
                    ]
                },
                {
                    test: /\.(woff(2)?|ttf|eot|svg)(\?v=\d+\.\d+\.\d+)?$/,
                    use: [{loader: 'file-loader', options: {name: '[name].[ext]', outputPath: 'fonts/'}}]
                }
            ]
        },
        plugins: [
            new ModuleFederationPlugin(getModuleFederationConfig(packageJson, {
                // library name is derived from package.json name:
                // camelCase('jahia-store') -> appShell.remotes.jahiaStore
                remotes: {
                    '@jahia/jcontent': 'appShell.remotes.jcontent'
                }
            })),
            new CleanWebpackPlugin({verbose: false}),
            new CopyWebpackPlugin({patterns: [{from: './package.json', to: ''}]}),
            new CycloneDxWebpackPlugin(cycloneDxWebpackPluginOptions)
        ],
        mode: 'development'
    };

    config.devtool = (argv.mode === 'production') ? 'source-map' : 'eval-source-map';
    return config;
};
