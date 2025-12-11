import { useState, useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { useTranslation } from 'react-i18next';

const LoginForm = ({ onToggleMode }) => {
  const [formData, setFormData] = useState({
    email: '',
    password: '',
  });
  const [loading, setLoading] = useState(false);
  const { login } = useAuth();
  const { t } = useTranslation();

  const handleChange = (e) => {
    setFormData({
      ...formData,
      [e.target.name]: e.target.value,
    });
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLoading(true);

    try {
      const result = await login(formData.email, formData.password);
      if (result.success) {
        // Redirect will be handled by the auth context
      }
    } catch (error) {
      console.error('Login error:', error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="card w-96 bg-base-100 shadow-xl">
      <div className="card-body">
        <h2 className="card-title text-2xl font-bold text-center mb-6">{t('Login')}</h2>
        
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="form-control">
            <label htmlFor="login-email" className="label">
              <span className="label-text">{t('Email')}</span>
            </label>
            <input
              id="login-email"
              type="email"
              name="email"
              value={formData.email}
              onChange={handleChange}
              className="input input-bordered"
              required
              aria-required="true"
              autoComplete="email"
            />
          </div>

          <div className="form-control">
            <label htmlFor="login-password" className="label">
              <span className="label-text">{t('Password')}</span>
            </label>
            <input
              id="login-password"
              type="password"
              name="password"
              value={formData.password}
              onChange={handleChange}
              className="input input-bordered"
              required
              aria-required="true"
              autoComplete="current-password"
            />
          </div>

          <div className="form-control mt-6">
            <button
              type="submit"
              className={`btn btn-primary ${loading ? 'loading' : ''}`}
              disabled={loading}
            >
              {loading ? t('Logging in...') : t('Login')}
            </button>
          </div>
        </form>

        <div className="divider">{t('OR')}</div>

        <div className="text-center">
          <p className="text-sm text-base-content/70">
            {t("Don't have an account?")}{' '}
            <button
              onClick={onToggleMode}
              className="link link-primary"
            >
              {t('Sign up')}
            </button>
          </p>
        </div>

      </div>
    </div>
  );
};

const CompanyRegistrationForm = ({ onToggleMode }) => {
  const [formData, setFormData] = useState({
    companyName: '',
    email: '',
    password: '',
    confirmPassword: '',
    firstName: '',
    lastName: '',
    phone: '',
  });
  const [documents, setDocuments] = useState([]);
  const [loading, setLoading] = useState(false);
  const { registerCompany } = useAuth();
  const { t } = useTranslation();

  const handleChange = (e) => {
    setFormData({
      ...formData,
      [e.target.name]: e.target.value,
    });
  };

  const handleFileChange = (e) => {
    const files = Array.from(e.target.files);
    // Validate file types
    const validTypes = ['application/pdf', 'image/png', 'image/jpeg', 'image/jpg'];
    const validFiles = files.filter(file => validTypes.includes(file.type));
    
    if (validFiles.length !== files.length) {
      alert(t('Only PDF, PNG, and JPG files are allowed'));
      return;
    }
    
    setDocuments(validFiles);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    
    if (formData.password !== formData.confirmPassword) {
      alert(t('Passwords do not match'));
      return;
    }

    setLoading(true);

    try {
      const result = await registerCompany(formData, documents);
      if (result.success) {
        // Reset form
        setFormData({
          companyName: '',
          email: '',
          password: '',
          confirmPassword: '',
          firstName: '',
          lastName: '',
          phone: '',
        });
        setDocuments([]);
        // Switch to login after successful registration
        setTimeout(() => {
          onToggleMode();
        }, 2000);
      }
    } catch (error) {
      console.error('Registration error:', error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="card w-full max-w-2xl bg-base-100 shadow-xl">
      <div className="card-body">
        <h2 className="card-title text-2xl font-bold text-center mb-6">{t('Company Registration')}</h2>
        
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="form-control">
            <label htmlFor="company-name" className="label">
              <span className="label-text">{t('Company Name *')}</span>
            </label>
            <input
              id="company-name"
              type="text"
              name="companyName"
              value={formData.companyName}
              onChange={handleChange}
              className="input input-bordered"
              required
              aria-required="true"
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="form-control">
              <label htmlFor="first-name" className="label">
                <span className="label-text">{t('First Name *')}</span>
              </label>
              <input
                id="first-name"
                type="text"
                name="firstName"
                value={formData.firstName}
                onChange={handleChange}
                className="input input-bordered"
                required
                aria-required="true"
                autoComplete="given-name"
              />
            </div>

            <div className="form-control">
              <label htmlFor="last-name" className="label">
                <span className="label-text">{t('Last Name *')}</span>
              </label>
              <input
                id="last-name"
                type="text"
                name="lastName"
                value={formData.lastName}
                onChange={handleChange}
                className="input input-bordered"
                required
                aria-required="true"
                autoComplete="family-name"
              />
            </div>
          </div>

          <div className="form-control">
            <label htmlFor="register-email" className="label">
              <span className="label-text">{t('Email *')}</span>
            </label>
            <input
              id="register-email"
              type="email"
              name="email"
              value={formData.email}
              onChange={handleChange}
              className="input input-bordered"
              required
              aria-required="true"
              autoComplete="email"
            />
          </div>

          <div className="form-control">
            <label htmlFor="register-phone" className="label">
              <span className="label-text">{t('Phone (Optional)')}</span>
            </label>
            <input
              id="register-phone"
              type="tel"
              name="phone"
              value={formData.phone}
              onChange={handleChange}
              className="input input-bordered"
              autoComplete="tel"
            />
          </div>

          <div className="form-control">
            <label htmlFor="register-password" className="label">
              <span className="label-text">{t('Password *')}</span>
            </label>
            <input
              id="register-password"
              type="password"
              name="password"
              value={formData.password}
              onChange={handleChange}
              className="input input-bordered"
              required
              aria-required="true"
              autoComplete="new-password"
            />
          </div>

          <div className="form-control">
            <label htmlFor="confirm-password" className="label">
              <span className="label-text">{t('Confirm Password *')}</span>
            </label>
            <input
              id="confirm-password"
              type="password"
              name="confirmPassword"
              value={formData.confirmPassword}
              onChange={handleChange}
              className="input input-bordered"
              required
              aria-required="true"
              autoComplete="new-password"
            />
          </div>

          <div className="form-control">
            <label htmlFor="business-documents" className="label">
              <span className="label-text">{t('Business Documents (PDF, PNG, JPG) *')}</span>
            </label>
            <input
              id="business-documents"
              type="file"
              multiple
              accept=".pdf,.png,.jpg,.jpeg"
              onChange={handleFileChange}
              className="file-input file-input-bordered"
              required
              aria-required="true"
            />
            <label className="label">
              <span className="label-text-alt">{t('Upload business registration documents, licenses, etc.')}</span>
            </label>
            {documents.length > 0 && (
              <div className="mt-2">
                <p className="text-sm text-base-content/70">{t('Selected files:')}</p>
                <ul className="list-disc list-inside text-sm">
                  {documents.map((doc, idx) => (
                    <li key={idx}>{doc.name}</li>
                  ))}
                </ul>
              </div>
            )}
          </div>

          <div className="form-control mt-6">
            <button
              type="submit"
              className={`btn btn-primary ${loading ? 'loading' : ''}`}
              disabled={loading}
            >
              {loading ? t('Submitting...') : t('Register Company')}
            </button>
          </div>
        </form>

        <div className="divider">{t('OR')}</div>

        <div className="text-center">
          <p className="text-sm text-base-content/70">
            {t('Already have an account?')}{' '}
            <button
              onClick={onToggleMode}
              className="link link-primary"
            >
              {t('Login')}
            </button>
          </p>
        </div>
      </div>
    </div>
  );
};

const AuthModal = () => {
  const [isLogin, setIsLogin] = useState(true);
  const location = useLocation();

  useEffect(() => {
    // Set the mode based on the current route
    if (location.pathname === '/register') {
      setIsLogin(false);
    } else if (location.pathname === '/login') {
      setIsLogin(true);
    }
  }, [location.pathname]);

  return (
    <div className="min-h-screen bg-base-200 flex items-center justify-center p-4">
      <div className="w-full max-w-md">
        {isLogin ? (
          <LoginForm onToggleMode={() => setIsLogin(false)} />
        ) : (
          <CompanyRegistrationForm onToggleMode={() => setIsLogin(true)} />
        )}
      </div>
    </div>
  );
};

export default AuthModal;

