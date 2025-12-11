import React, { useState, useEffect } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { CheckIcon, XIcon, UsersIcon, ClockIcon, AlertCircleIcon, BanIcon, UnlockIcon } from 'lucide-react';
import { useTranslation } from 'react-i18next';

const ConsumerRequests = () => {
  const { api, hasAnyRole } = useAuth();
  const [pendingRequests, setPendingRequests] = useState([]);
  const [allRequests, setAllRequests] = useState([]);
  const [consumers, setConsumers] = useState([]);
  const [blockedConsumers, setBlockedConsumers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [activeTab, setActiveTab] = useState('pending');
  const canManageAccess = hasAnyRole(['Owner', 'Manager']);
  const { t } = useTranslation();

  // Fetch pending requests
  const normalizeRequestStatus = (request) => {
    if (!request) return request;
    if (request.status === 'cancelled' || request.status === 'revoked') {
      return { ...request, status: 'pending', _statusAlias: 'pending' };
    }
    return request;
  };

  const applyStatusNormalization = (list = []) => list.map(normalizeRequestStatus);

  const fetchPendingRequests = async () => {
    try {
      const response = await api.get('/company/consumers/requests/pending', {
        params: { ts: Date.now() },
        headers: {
          'Cache-Control': 'no-cache',
          'Pragma': 'no-cache',
          'If-Modified-Since': '0',
        },
      });
      console.log('[ConsumerRequests] Pending requests response:', response.data);
      if (response.data.success) {
        setPendingRequests(applyStatusNormalization(response.data.data));
      }
    } catch (err) {
      setError(t('Failed to fetch pending requests'));
      console.error('Error fetching pending requests:', err);
    }
  };

  // Fetch all requests
  const fetchAllRequests = async () => {
    try {
      const response = await api.get('/company/consumers/requests', {
        params: { ts: Date.now() },
        headers: {
          'Cache-Control': 'no-cache',
          'Pragma': 'no-cache',
          'If-Modified-Since': '0',
        },
      });
      console.log('[ConsumerRequests] All requests response:', response.data);
      if (response.data.success) {
        setAllRequests(applyStatusNormalization(response.data.data));
      }
    } catch (err) {
      setError(t('Failed to fetch requests'));
      console.error('Error fetching requests:', err);
    }
  };

  // Fetch consumers with access
  const fetchConsumers = async () => {
    try {
      const response = await api.get('/company/consumers/consumers', {
        params: { ts: Date.now() },
        headers: {
          'Cache-Control': 'no-cache',
          'Pragma': 'no-cache',
          'If-Modified-Since': '0',
        },
      });
      console.log('[ConsumerRequests] Active consumers response:', response.data);
      if (response.data.success) {
        setConsumers(response.data.data);
      }
    } catch (err) {
      setError(t('Failed to fetch consumers'));
      console.error('Error fetching consumers:', err);
    }
  };

  const fetchBlockedConsumers = async () => {
    try {
      const response = await api.get('/company/consumers/consumers/blocked', {
        params: { ts: Date.now() },
        headers: {
          'Cache-Control': 'no-cache',
          'Pragma': 'no-cache',
          'If-Modified-Since': '0',
        },
      });
      console.log('[ConsumerRequests] Blocked consumers response:', response.data);
      if (response.data.success) {
        setBlockedConsumers(response.data.data);
      }
    } catch (err) {
      setError(t('Failed to fetch blocked consumers'));
      console.error('Error fetching blocked consumers:', err);
    }
  };

  const refreshAllData = async () => {
    try {
      await Promise.all([
        fetchPendingRequests(),
        fetchAllRequests(),
        fetchConsumers(),
        canManageAccess ? fetchBlockedConsumers() : Promise.resolve(),
      ]);
    } catch (err) {
      console.error('Error refreshing data:', err);
    }
  };

  const blockConsumer = async (consumerId, consumerName) => {
    if (!canManageAccess) return;
    const displayName = consumerName || t('this consumer');
    const confirmed = window.confirm(
      t('Block {{name}}? They will lose access and cannot request again until unblocked.', { name: displayName })
    );
    if (!confirmed) return;

    try {
      const response = await api.post(`/company/consumers/consumers/${consumerId}/block`);
      if (response.data.success) {
        alert(
          t('{{name}} has been blocked.', { name: consumerName || t('Consumer') })
        );
        await refreshAllData();
      }
    } catch (err) {
      setError(t('Failed to block consumer'));
      console.error('Error blocking consumer:', err);
    }
  };

  const unblockConsumer = async (consumerId, consumerName) => {
    if (!canManageAccess) return;
    const displayName = consumerName || t('this consumer');
    const confirmed = window.confirm(
      t('Unblock {{name}}? They will be able to request access again.', { name: displayName })
    );
    if (!confirmed) return;

    try {
      const response = await api.post(`/company/consumers/consumers/${consumerId}/unblock`);
      if (response.data.success) {
        alert(
          t('{{name}} has been unblocked.', { name: consumerName || t('Consumer') })
        );
        await refreshAllData();
      }
    } catch (err) {
      setError(t('Failed to unblock consumer'));
      console.error('Error unblocking consumer:', err);
    }
  };

  const cancelRequest = async (requestId) => {
    if (!canManageAccess) return;
    const confirmed = window.confirm(
      t('Cancel this consumer request? They will return to the pending state and need to re-request access.')
    );
    if (!confirmed) return;

    try {
      const response = await api.post(`/company/consumers/requests/${requestId}/cancel`);
      if (response.data.success) {
        alert(t('Request cancelled.'));
        await refreshAllData();
      }
    } catch (err) {
      setError(t('Failed to cancel request'));
      console.error('Error cancelling request:', err);
    }
  };


  useEffect(() => {
    const initialise = async () => {
      setLoading(true);
      await refreshAllData();
      setLoading(false);
    };
    initialise();
  }, [canManageAccess]);

  useEffect(() => {
    const refreshCurrentTab = async () => {
      try {
        if (activeTab === 'pending') {
          await fetchPendingRequests();
        } else if (activeTab === 'all') {
          await fetchAllRequests();
        } else if (activeTab === 'consumers') {
          await fetchConsumers();
        } else if (activeTab === 'blocked' && canManageAccess) {
          await fetchBlockedConsumers();
        }
      } catch (err) {
        console.error('Error refreshing tab data:', err);
      }
    };

    refreshCurrentTab();
  }, [activeTab, canManageAccess]);

  useEffect(() => {
    const interval = setInterval(() => {
      refreshAllData();
    }, 15000);

    return () => clearInterval(interval);
  }, [canManageAccess]);

  // Approve request
  const approveRequest = async (requestId) => {
    try {
      const response = await api.post(`/company/consumers/requests/${requestId}/approve`);
      if (response.data.success) {
        alert(t('Access approved for {{name}}', { name: response.data.data.consumerName }));
        await refreshAllData();
      }
    } catch (err) {
      setError(t('Failed to approve request'));
      console.error('Error approving request:', err);
    }
  };

  // Reject request
  const rejectRequest = async (requestId) => {
    try {
      const response = await api.post(`/company/consumers/requests/${requestId}/reject`);
      if (response.data.success) {
        alert(t('Access rejected for {{name}}', { name: response.data.data.consumerName }));
        await refreshAllData();
      }
    } catch (err) {
      setError(t('Failed to reject request'));
      console.error('Error rejecting request:', err);
    }
  };

  // Unlink access
  const unlinkAccess = async (accessId) => {
    if (!canManageAccess) return;
    if (window.confirm(t("Are you sure you want to unlink this consumer's access?"))) {
      try {
        const response = await api.post(`/company/consumers/consumers/${accessId}/revoke`);
        if (response.data.success) {
          alert(t('Access unlinked for {{name}}', { name: response.data.data.consumerName }));
          await refreshAllData();
        }
      } catch (err) {
        setError(t('Failed to unlink access'));
        console.error('Error unlinking access:', err);
      }
    }
  };

  const getStatusColor = (status) => {
    switch (status) {
      case 'pending': return 'text-yellow-600 bg-yellow-100';
      case 'approved': return 'text-green-600 bg-green-100';
      case 'rejected': return 'text-red-600 bg-red-100';
      case 'revoked': return 'text-orange-600 bg-orange-100';
      case 'blocked': return 'text-red-700 bg-red-100';
      case 'cancelled': return 'text-gray-600 bg-gray-200';
      default: return 'text-gray-600 bg-gray-100';
    }
  };

  const getStatusIcon = (status) => {
    switch (status) {
      case 'pending': return <ClockIcon className="size-4" />;
      case 'approved': return <CheckIcon className="size-4" />;
      case 'rejected': return <XIcon className="size-4" />;
      case 'blocked': return <BanIcon className="size-4" />;
      default: return <AlertCircleIcon className="size-4" />;
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center items-center min-h-screen">
        <div className="loading loading-spinner loading-lg" />
      </div>
    );
  }

  return (
    <div className="container mx-auto px-4 py-8 max-w-6xl">
      <div className="mb-8">
        <h1 className="text-3xl font-bold mb-2">{t('Consumer Requests')}</h1>
        <p className="text-base-content/70">
          {t('Manage consumer access requests to your company catalog')}
        </p>
      </div>

      {error && (
        <div className="alert alert-error mb-6">
          <AlertCircleIcon className="size-6" />
          <span>{error}</span>
        </div>
      )}

      {/* Tab Navigation */}
      <div className="tabs tabs-boxed mb-6">
        <button 
          className={`tab ${activeTab === 'pending' ? 'tab-active' : ''}`}
          onClick={() => setActiveTab('pending')}
        >
          <ClockIcon className="size-4 mr-2" />
          {t('Pending Requests')} ({pendingRequests.length})
        </button>
        <button 
          className={`tab ${activeTab === 'all' ? 'tab-active' : ''}`}
          onClick={() => setActiveTab('all')}
        >
          <UsersIcon className="size-4 mr-2" />
          {t('All Requests')} ({allRequests.length})
        </button>
        <button 
          className={`tab ${activeTab === 'consumers' ? 'tab-active' : ''}`}
          onClick={() => setActiveTab('consumers')}
        >
          <CheckIcon className="size-4 mr-2" />
          {t('Active Consumers')}
        </button>
        {canManageAccess && (
          <button 
            className={`tab ${activeTab === 'blocked' ? 'tab-active' : ''}`}
            onClick={() => setActiveTab('blocked')}
          >
            <BanIcon className="size-4 mr-2" />
            {t('Blocked')}
          </button>
        )}
      </div>

      {/* Pending Requests */}
      {activeTab === 'pending' && (
        <div className="space-y-4">
          {pendingRequests.length === 0 ? (
            <div className="text-center py-12">
              <ClockIcon className="size-16 mx-auto text-gray-400 mb-4" />
              <h3 className="text-xl font-semibold text-gray-600 mb-2">{t('No Pending Requests')}</h3>
              <p className="text-gray-500">{t('No consumers have requested access to your catalog yet.')}</p>
            </div>
          ) : (
            pendingRequests.map((request) => (
              <div key={request.id} className="card bg-base-100 shadow-lg">
                <div className="card-body">
                  <div className="flex justify-between items-start">
                    <div className="flex-1">
                      <h3 className="card-title text-lg">
                        {request.first_name} {request.last_name}
                      </h3>
                      <p className="text-sm text-gray-600 mb-2">{request.email}</p>
                      {request.phone && (
                        <p className="text-sm text-gray-600 mb-2">{t('Phone: {{phone}}', { phone: request.phone })}</p>
                      )}
                      <p className="text-sm text-gray-500">
                        {t('Requested: {{date}}', { date: new Date(request.created_at).toLocaleDateString() })}
                      </p>
                    </div>
                    <div className="flex gap-2 flex-wrap">
                      <button 
                        className="btn btn-success btn-sm"
                        onClick={() => approveRequest(request.id)}
                      >
                        <CheckIcon className="size-4 mr-1" />
                        {t('Approve')}
                      </button>
                      <button 
                        className="btn btn-error btn-sm"
                        onClick={() => rejectRequest(request.id)}
                      >
                        <XIcon className="size-4 mr-1" />
                        {t('Reject')}
                      </button>
                      {canManageAccess && (
                        <button 
                          className="btn btn-warning btn-sm"
                          onClick={() => blockConsumer(request.consumer_id, `${request.first_name} ${request.last_name}`)}
                        >
                          <BanIcon className="size-4 mr-1" />
                          {t('Block')}
                        </button>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            ))
          )}
        </div>
      )}

      {/* All Requests */}
      {activeTab === 'all' && (
        <div className="space-y-4">
          {allRequests.length === 0 ? (
            <div className="text-center py-12">
              <UsersIcon className="size-16 mx-auto text-gray-400 mb-4" />
              <h3 className="text-xl font-semibold text-gray-600 mb-2">{t('No Requests')}</h3>
              <p className="text-gray-500">{t('No consumer requests have been made yet.')}</p>
            </div>
          ) : (
            allRequests.map((request) => (
              <div key={request.id} className="card bg-base-100 shadow-lg">
                <div className="card-body">
                  <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
                    <div className="flex-1">
                      <div className="flex items-center gap-3 mb-2">
                        <h3 className="card-title text-lg">
                          {request.first_name} {request.last_name}
                        </h3>
                        <span className={`badge ${getStatusColor(request.status)}`}>
                          {getStatusIcon(request.status)}
                          <span className="ml-1 capitalize">{t(request.status)}</span>
                        </span>
                      </div>
                      <p className="text-sm text-gray-600 mb-2">{request.email}</p>
                      {request.phone && (
                        <p className="text-sm text-gray-600 mb-2">{t('Phone: {{phone}}', { phone: request.phone })}</p>
                      )}
                      <p className="text-sm text-gray-500">
                        {t('Requested: {{date}}', { date: new Date(request.created_at).toLocaleDateString() })}
                      </p>
                      {request.updated_at !== request.created_at && (
                        <p className="text-sm text-gray-500">
                          {t('Updated: {{date}}', { date: new Date(request.updated_at).toLocaleDateString() })}
                        </p>
                      )}
                      {request.reviewed_by_first_name && (
                        <p className="text-sm text-gray-500">
                          {t('Reviewed by: {{name}}', { name: `${request.reviewed_by_first_name} ${request.reviewed_by_last_name}`.trim() })}
                        </p>
                      )}
                    </div>
                    {canManageAccess && (
                      <div className="flex gap-2 flex-wrap">
                        {request.status === 'approved' && request.access_id && (
                          <button
                            className="btn btn-warning btn-sm"
                            onClick={() => unlinkAccess(request.access_id)}
                          >
                            <XIcon className="size-4 mr-1" />
                            {t('Unlink Access')}
                          </button>
                        )}
                        {request.consumer_id && (
                          request.is_blocked || request.status === 'blocked' ? (
                            <button
                              className="btn btn-success btn-sm"
                              onClick={() => unblockConsumer(request.consumer_id, `${request.first_name} ${request.last_name}`)}
                            >
                              <UnlockIcon className="size-4 mr-1" />
                              {t('Unblock')}
                            </button>
                          ) : (
                            <button
                              className="btn btn-error btn-sm"
                              onClick={() => blockConsumer(request.consumer_id, `${request.first_name} ${request.last_name}`)}
                            >
                              <BanIcon className="size-4 mr-1" />
                              {t('Block')}
                            </button>
                          )
                        )}
                        {['pending', 'approved'].includes(request.status) && (
                          <button
                            className="btn btn-outline btn-sm"
                            onClick={() => cancelRequest(request.id)}
                          >
                            <XIcon className="size-4 mr-1" />
                            {t('Cancel Request')}
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                </div>
              </div>
            ))
          )}
        </div>
      )}

      {/* Active Consumers */}
      {activeTab === 'consumers' && (
        <div className="space-y-4">
          {consumers.length === 0 ? (
            <div className="text-center py-12">
              <CheckIcon className="size-16 mx-auto text-gray-400 mb-4" />
              <h3 className="text-xl font-semibold text-gray-600 mb-2">{t('No Active Consumers')}</h3>
              <p className="text-gray-500">{t('No consumers currently have access to your catalog.')}</p>
            </div>
          ) : (
            consumers.map((consumer) => (
              <div key={consumer.id} className="card bg-base-100 shadow-lg">
                <div className="card-body">
                  <div className="flex justify-between items-start">
                    <div className="flex-1">
                      <div className="flex items-center gap-3 mb-2">
                        <h3 className="card-title text-lg">
                          {consumer.first_name} {consumer.last_name}
                        </h3>
                        <span className={`badge ${consumer.is_active ? 'badge-success' : 'badge-error'}`}>
                          {consumer.is_active ? t('Active') : t('Inactive')}
                        </span>
                      </div>
                      <p className="text-sm text-gray-600 mb-2">{consumer.email}</p>
                      {consumer.phone && (
                        <p className="text-sm text-gray-600 mb-2">{t('Phone: {{phone}}', { phone: consumer.phone })}</p>
                      )}
                      <p className="text-sm text-gray-500">
                        {t('Access granted: {{date}}', { date: new Date(consumer.access_granted_at).toLocaleDateString() })}
                      </p>
                      {consumer.granted_by_first_name && (
                        <p className="text-sm text-gray-500">
                          {t('Granted by: {{name}}', { name: `${consumer.granted_by_first_name} ${consumer.granted_by_last_name}`.trim() })}
                        </p>
                      )}
                    </div>
                    {consumer.is_active && canManageAccess && (
                      <div className="flex gap-2 flex-wrap">
                        <button 
                          className="btn btn-error btn-sm"
                          onClick={() => unlinkAccess(consumer.id)}
                        >
                          <XIcon className="size-4 mr-1" />
                          {t('Unlink Access')}
                        </button>
                        <button
                          className="btn btn-warning btn-sm"
                          onClick={() => blockConsumer(consumer.consumer_id, `${consumer.first_name} ${consumer.last_name}`)}
                        >
                          <BanIcon className="size-4 mr-1" />
                          {t('Block')}
                        </button>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            ))
          )}
        </div>
      )}

      {canManageAccess && activeTab === 'blocked' && (
        <div className="space-y-4">
          {blockedConsumers.length === 0 ? (
            <div className="text-center py-12">
              <BanIcon className="size-16 mx-auto text-gray-400 mb-4" />
              <h3 className="text-xl font-semibold text-gray-600 mb-2">{t('No Blocked Consumers')}</h3>
              <p className="text-gray-500">{t('No consumers are currently blocked.')}</p>
            </div>
          ) : (
            blockedConsumers.map((consumer) => (
              <div key={consumer.id} className="card bg-base-100 shadow-lg">
                <div className="card-body">
                  <div className="flex justify-between items-start">
                    <div className="flex-1">
                      <h3 className="card-title text-lg">
                        {consumer.first_name} {consumer.last_name}
                      </h3>
                      <p className="text-sm text-gray-600 mb-2">{consumer.email}</p>
                      {consumer.phone && (
                        <p className="text-sm text-gray-600 mb-2">{t('Phone: {{phone}}', { phone: consumer.phone })}</p>
                      )}
                      <p className="text-sm text-gray-500">
                        {t('Blocked: {{date}}', { date: new Date(consumer.blocked_at).toLocaleDateString() })}
                      </p>
                      {consumer.blocked_by_first_name && (
                        <p className="text-sm text-gray-500">
                          {t('Blocked by: {{name}}', { name: `${consumer.blocked_by_first_name} ${consumer.blocked_by_last_name}`.trim() })}
                        </p>
                      )}
                    </div>
                    {canManageAccess && (
                      <button 
                        className="btn btn-success btn-sm"
                        onClick={() => unblockConsumer(consumer.consumer_id, `${consumer.first_name} ${consumer.last_name}`)}
                      >
                        <UnlockIcon className="size-4 mr-1" />
                        {t('Unblock')}
                      </button>
                    )}
                  </div>
                </div>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  );
};

export default ConsumerRequests;
