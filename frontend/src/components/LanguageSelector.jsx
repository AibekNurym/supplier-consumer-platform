import { useTranslation } from 'react-i18next';

const AVAILABLE_LANGUAGES = [
  { code: 'en', labelKey: 'English' },
  { code: 'ru', labelKey: 'Русский' },
  { code: 'kk', labelKey: 'Қазақша' },
];

function LanguageSelector() {
  const { i18n, t } = useTranslation();

  const handleChange = (event) => {
    const { value } = event.target;
    i18n.changeLanguage(value);
  };

  return (
    <label className="flex items-center gap-2">
      <span className="text-sm text-base-content/70 hidden lg:inline-block">
        {t('Language')}
      </span>
      <select
        className="select select-bordered select-sm min-w-[6.5rem]"
        value={i18n.language ?? 'en'}
        onChange={handleChange}
        aria-label={t('Select language')}
      >
        {AVAILABLE_LANGUAGES.map(({ code, labelKey }) => (
          <option key={code} value={code}>
            {t(labelKey)}
          </option>
        ))}
      </select>
    </label>
  );
}

export default LanguageSelector;



