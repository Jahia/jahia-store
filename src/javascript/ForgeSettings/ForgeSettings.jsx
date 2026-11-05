import React, {useEffect, useState} from 'react';
import {useMutation, useQuery} from '@apollo/client';
import {useTranslation} from 'react-i18next';
import {Button, Field, Input} from '@jahia/moonstone';
import styles from './ForgeSettings.scss';
import {GET_FORGE_SETTINGS, GET_SITE_IMAGES, UPDATE_FORGE_SETTINGS} from './ForgeSettings.gql';

/** Edit-workspace file URL for a media node path (jContent renders the default workspace). */
function fileUrl(path) {
    return encodeURI('/files/default' + path);
}

// Footer link fields rendered as a list (key = i18n label, state field, GraphQL var).
const FOOTER_FIELDS = [
    {name: 'copyright', label: 'label.copyright'},
    {name: 'privacyUrl', label: 'label.privacyUrl'},
    {name: 'termsUrl', label: 'label.termsUrl'},
    {name: 'cookiesUrl', label: 'label.cookiesUrl'},
    {name: 'facebookUrl', label: 'label.facebookUrl'},
    {name: 'linkedinUrl', label: 'label.linkedinUrl'},
    {name: 'twitterUrl', label: 'label.twitterUrl'},
    {name: 'youtubeUrl', label: 'label.youtubeUrl'}
];

export function ForgeSettings({siteKey}) {
    const {t} = useTranslation('jahia-store');

    const [url, setUrl] = useState('');
    const [id, setId] = useState('');
    const [user, setUser] = useState('');
    const [password, setPassword] = useState('');
    const [passwordSet, setPasswordSet] = useState(false);
    const [logoPath, setLogoPath] = useState('');
    const [footer, setFooter] = useState({});
    const [saveStatus, setSaveStatus] = useState(null);

    useEffect(() => {
        const prev = document.title;
        document.title = `${t('label.title')} — ${siteKey} — Jahia`;
        return () => {
            document.title = prev;
        };
    }, [siteKey, t]);

    const {data, loading, error} = useQuery(GET_FORGE_SETTINGS, {
        variables: {siteKey},
        fetchPolicy: 'network-only'
    });

    // Site media images for the logo picker (tolerate a site with no media library yet).
    const {data: imagesData} = useQuery(GET_SITE_IMAGES, {
        variables: {path: `/sites/${siteKey}/files`},
        fetchPolicy: 'network-only',
        errorPolicy: 'all'
    });
    const images =
        (imagesData &&
            imagesData.jcr &&
            imagesData.jcr.nodeByPath &&
            imagesData.jcr.nodeByPath.descendants &&
            imagesData.jcr.nodeByPath.descendants.nodes) ||
        [];

    const [updateForgeSettings, {loading: saving}] = useMutation(UPDATE_FORGE_SETTINGS, {
        refetchQueries: [{query: GET_FORGE_SETTINGS, variables: {siteKey}}]
    });

    const syncFromSettings = s => {
        setUrl(s.url || '');
        setId(s.id || '');
        setUser(s.user || '');
        setPassword('');
        setPasswordSet(Boolean(s.passwordSet));
        setLogoPath(s.logoPath || '');
        const links = s.footerLinks || {};
        setFooter({
            copyright: s.copyright || '',
            privacyUrl: links.privacyUrl || '',
            termsUrl: links.termsUrl || '',
            cookiesUrl: links.cookiesUrl || '',
            facebookUrl: links.facebookUrl || '',
            linkedinUrl: links.linkedinUrl || '',
            twitterUrl: links.twitterUrl || '',
            youtubeUrl: links.youtubeUrl || ''
        });
    };

    // Re-seed the form whenever the query yields a NEW settings object (initial load,
    // and the refetch after a save). Done during render with a guard - React's
    // "adjusting state when a prop changes" pattern - instead of a useEffect, which
    // would render once with stale fields and then cascade a second render
    // (react-hooks/set-state-in-effect). Apollo keeps the same object reference while
    // the cache is unchanged, so this runs once per actual result.
    const settings = data && data.forge && data.forge.settings;
    const [syncedSettings, setSyncedSettings] = useState(null);
    if (settings && settings !== syncedSettings) {
        setSyncedSettings(settings);
        syncFromSettings(settings);
    }

    const setFooterField = (name, value) => setFooter(prev => ({...prev, [name]: value}));

    const handleSubmit = async () => {
        setSaveStatus('saving');
        try {
            const result = await updateForgeSettings({
                variables: {
                    siteKey,
                    url: url || null,
                    id: id || null,
                    user: user || null,
                    // Blank password = keep existing (matches the legacy flow's behavior).
                    password: password || null,
                    // Blank logo clears it; footer blanks clear those fields.
                    logo: logoPath || '',
                    copyright: footer.copyright || null,
                    privacyUrl: footer.privacyUrl || null,
                    termsUrl: footer.termsUrl || null,
                    cookiesUrl: footer.cookiesUrl || null,
                    facebookUrl: footer.facebookUrl || null,
                    linkedinUrl: footer.linkedinUrl || null,
                    twitterUrl: footer.twitterUrl || null,
                    youtubeUrl: footer.youtubeUrl || null
                }
            });
            setSaveStatus(result.data && result.data.forge && result.data.forge.updateSettings ? 'success' : 'error');
            setPassword('');
        } catch {
            // The failure is surfaced to the user via the 'error' save status; avoid
            // console logging in production (consistent with the jahia-store-template islands).
            setSaveStatus('error');
        }
    };

    const handleCancel = () => {
        if (data && data.forge && data.forge.settings) {
            syncFromSettings(data.forge.settings);
        }

        setSaveStatus('cancel');
    };

    if (loading) {
        return <div className={styles.forge_loading}>{t('label.loading')}</div>;
    }

    if (error) {
        return (
            <div className={styles.forge_error} role="alert">
                {t('errors.load.failed')}: {error.message}
            </div>
        );
    }

    return (
        <div>
            <div className={styles.forge_page_header}>
                <h2>{t('label.title')} — {siteKey}</h2>
            </div>
            <div className={styles.forge_container}>
                {saveStatus === 'success' && (
                    <div className={`${styles.forge_alert} ${styles.success}`} role="status">
                        {t('success.update')}
                    </div>
                )}
                {saveStatus === 'error' && (
                    <div className={`${styles.forge_alert} ${styles.error}`} role="alert">
                        {t('errors.update.failed')}
                    </div>
                )}

                <div className={styles.forge_form}>
                    <Field label={t('label.url')} id="forge-url">
                        <Input
                            id="forge-url"
                            value={url}
                            placeholder={t('label.urlPlaceholder')}
                            onChange={e => setUrl(e.target.value)}
                        />
                    </Field>

                    <Field label={t('label.id')} id="forge-id">
                        <Input id="forge-id" value={id} onChange={e => setId(e.target.value)}/>
                    </Field>

                    <Field label={t('label.user')} id="forge-user">
                        <Input id="forge-user" value={user} onChange={e => setUser(e.target.value)}/>
                    </Field>

                    <Field label={t('label.password')} id="forge-password">
                        <Input
                            id="forge-password"
                            type="password"
                            value={password}
                            placeholder={passwordSet ? t('label.passwordUnchanged') : ''}
                            onChange={e => setPassword(e.target.value)}
                        />
                        <div className={styles.forge_password_hint}>
                            {passwordSet ? t('label.passwordReplaceHint') : t('label.passwordNewHint')}
                        </div>
                    </Field>

                    <h3 className={styles.forge_section}>{t('branding.title')}</h3>

                    <Field label={t('label.logo')} id="forge-logo">
                        <div className={styles.forge_logo_current} data-logo-path={logoPath}>
                            {logoPath ? (
                                <img className={styles.forge_logo_preview} src={fileUrl(logoPath)} alt={t('label.logo')}/>
                            ) : (
                                <span className={styles.forge_logo_none}>{t('label.logoNone')}</span>
                            )}
                            {logoPath && (
                                <Button
                                    type="button"
                                    size="small"
                                    variant="ghost"
                                    label={t('label.logoRemove')}
                                    onClick={() => setLogoPath('')}
                                />
                            )}
                        </div>
                        {images.length === 0 ? (
                            <div className={styles.forge_password_hint}>{t('label.logoNoImages')}</div>
                        ) : (
                            <div className={styles.forge_logo_grid} role="group" aria-label={t('label.logoPick')}>
                                {images.map(img => (
                                    <button
                                        key={img.uuid}
                                        type="button"
                                        aria-pressed={img.path === logoPath}
                                        title={img.name}
                                        data-image-path={img.path}
                                        className={
                                            img.path === logoPath
                                                ? `${styles.forge_logo_choice} ${styles.selected}`
                                                : styles.forge_logo_choice
                                        }
                                        onClick={() => setLogoPath(img.path)}
                                    >
                                        <img src={fileUrl(img.path)} alt={img.name}/>
                                    </button>
                                ))}
                            </div>
                        )}
                    </Field>

                    <h3 className={styles.forge_section}>{t('footer.title')}</h3>

                    {FOOTER_FIELDS.map(f => (
                        <Field key={f.name} label={t(f.label)} id={`forge-${f.name}`}>
                            <Input
                                id={`forge-${f.name}`}
                                value={footer[f.name] || ''}
                                onChange={e => setFooterField(f.name, e.target.value)}
                            />
                        </Field>
                    ))}

                    <div className={styles.forge_actions}>
                        <Button
                            type="button"
                            label={saving ? t('label.saving') : t('label.save')}
                            variant="primary"
                            isDisabled={saving}
                            onClick={handleSubmit}
                        />
                        <Button
                            type="button"
                            label={t('label.cancel')}
                            variant="secondary"
                            isDisabled={saving}
                            onClick={handleCancel}
                        />
                    </div>
                </div>
            </div>
        </div>
    );
}

export default ForgeSettings;
