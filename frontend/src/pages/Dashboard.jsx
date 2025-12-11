import { useNavigate } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';
import { useTranslation } from 'react-i18next';

const DashboardCard = ({ title, description, icon, onClick, className = "" }) => (
  <div 
    className={`card bg-base-100 shadow-xl cursor-pointer hover:shadow-2xl transition-all duration-300 ${className}`}
    onClick={onClick}
  >
    <div className="card-body text-center">
      <div className="text-6xl mb-4">{icon}</div>
      <h2 className="card-title justify-center">{title}</h2>
      <p className="text-base-content/70">{description}</p>
    </div>
  </div>
);

const OwnerDashboard = () => {
  const { user } = useAuth();
  const navigate = useNavigate();
  const { t } = useTranslation();

  const dashboardItems = [
    {
      title: t('Manage Products'),
      description: t('Create, update, and manage products'),
      icon: "📦",
      onClick: () => navigate('/products'),
      className: "hover:scale-105",
    },
    {
      title: t('User Management'),
      description: t('Create and manage company user accounts'),
      icon: "👥",
      onClick: () => navigate('/users'),
      className: "hover:scale-105",
    },
    {
      title: t('Consumer Access'),
      description: t('Review and approve consumer access requests'),
      icon: "🤝",
      onClick: () => navigate('/consumers'),
      className: "hover:scale-105",
    },
    {
      title: t('Admin Panel'),
      description: t('Access advanced administration tools'),
      icon: "🛡️",
      onClick: () => navigate('/admin'),
      className: "hover:scale-105",
    },
  ];

  return (
    <div className="container mx-auto p-6">
      <div className="mb-8">
        <h1 className="text-4xl font-bold mb-2">{t('Owner Dashboard')}</h1>
        <p className="text-lg text-base-content/70">
          {t('Welcome back, {{name}}! You have full control over the system.', { name: user?.firstName ?? '' })}
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {dashboardItems.map((item, index) => (
          <DashboardCard key={index} {...item} />
        ))}
      </div>
    </div>
  );
};

const ManagerDashboard = () => {
  const { user } = useAuth();
  const navigate = useNavigate();
  const { t } = useTranslation();

  const dashboardItems = [
    {
      title: t('Manage Products'),
      description: t('Keep product inventory up to date'),
      icon: "📦",
      onClick: () => navigate('/products'),
      className: "hover:scale-105",
    },
    {
      title: t('Consumer Access'),
      description: t('Approve or reject consumer access requests'),
      icon: "🤝",
      onClick: () => navigate('/consumers'),
      className: "hover:scale-105",
    },
  ];

  return (
    <div className="container mx-auto p-6">
      <div className="mb-8">
        <h1 className="text-4xl font-bold mb-2">{t('Manager Dashboard')}</h1>
        <p className="text-lg text-base-content/70">
          {t('Welcome back, {{name}}! Manage your team and operations.', { name: user?.firstName ?? '' })}
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {dashboardItems.map((item, index) => (
          <DashboardCard key={index} {...item} />
        ))}
      </div>
    </div>
  );
};

const SalesDashboard = () => {
  const { user } = useAuth();
  const navigate = useNavigate();
  const { t } = useTranslation();

  const dashboardItems = [
    {
      title: t('Product Catalog'),
      description: t('Browse available products to sell'),
      icon: "📦",
      onClick: () => navigate('/products'),
      className: "hover:scale-105",
    },
  ];

  return (
    <div className="container mx-auto p-6">
      <div className="mb-8">
        <h1 className="text-4xl font-bold mb-2">{t('Sales Dashboard')}</h1>
        <p className="text-lg text-base-content/70">
          {t('Welcome back, {{name}}! Focus on your sales activities.', { name: user?.firstName ?? '' })}
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {dashboardItems.map((item, index) => (
          <DashboardCard key={index} {...item} />
        ))}
      </div>
    </div>
  );
};

const Dashboard = () => {
  const { user, hasRole } = useAuth();
  const { t } = useTranslation();

  if (!user) {
    return (
      <div className="min-h-screen bg-base-200 flex items-center justify-center">
        <div className="loading loading-spinner loading-lg"></div>
      </div>
    );
  }

  // Render different dashboards based on user role
  if (hasRole('Owner')) {
    return <OwnerDashboard />;
  } else if (hasRole('Manager')) {
    return <ManagerDashboard />;
  } else if (hasRole('Sales Representative')) {
    return <SalesDashboard />;
  }

  // Fallback for unknown roles
  return (
    <div className="container mx-auto p-6">
      <div className="alert alert-warning">
        <span>{t('Unknown role: {{role}}. Please contact your administrator.', { role: user.roleName })}</span>
      </div>
    </div>
  );
};

export default Dashboard;


